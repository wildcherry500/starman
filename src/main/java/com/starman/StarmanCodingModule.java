package com.starman;

import com.rpl.agentorama.AgentModule;
import com.rpl.agentorama.AgentNode;
import com.rpl.agentorama.AgentTopology;
import com.rpl.agentorama.store.KeyValueStore;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starman Coding Agent — the spine.
 *
 * Graph:  generate → compile-validate → (accept | surface | generate)
 *
 * - generate:          builds prompt from $$system-prompt + $$accepted-patterns,
 *                      calls the generator model, extracts Java source
 * - compile-validate:  ground-truth javac compile against the live classpath;
 *                      success → accept; failure → loop back to generate with
 *                      the raw javac errors (max MAX_REVISIONS); exhausted → surface
 * - accept:            records the attempt, returns the working code
 * - surface:           records the attempt, returns errors for human review
 *
 * Deliberately NOT here yet (next stepping stones):
 * - LLM ValidateNode (checklist scoring against verified API patterns)
 * - *codegen-attempts depot (event-sourced history) — recordAttempt() is the
 *   seam; swap its body for a depot append when adding the PromptAgent
 */
public class StarmanCodingModule extends AgentModule {

  static final int MAX_REVISIONS = 3;  // total attempts = 1 + MAX_REVISIONS - 1 loops

  // Prompt baseline: Jun 9 2026 — verified patterns: single-node, KeyValueStore pipeline, branching router
  static final String DEFAULT_SYSTEM_PROMPT =
      "You are an expert Java engineer for Red Planet Labs' Agent-o-rama framework. "
    + "Generate a single, complete, compilable Java file. Rules:\n"
    + "- Output ONLY one Java code block, no prose outside it.\n"
    + "- Exactly one public top-level class; the filename will match it.\n"
    + "- Store names must start with $$. Depot names must start with *.\n"
    + "- Every agent graph path must end with agentNode.result(...).\n"
    + "- Use HashMap/ArrayList, never Map.of()/List.of() for stored values.\n"
    + "- NEVER use topology.agent(...). ALWAYS use topology.newAgent(\"Name\")"
    + ".node(\"nodeName\", null, (AgentNode agentNode, Type input) -> {...}).\n"
    + "- Node lambdas MUST declare explicit parameter types ALWAYS — write (AgentNode agentNode, String input) never (agentNode, input).\n"
    + "- ALWAYS declare stores before newAgent() using topology.declareKeyValueStore(\"$$name\", KeyClass.class, ValueClass.class).\n"
    + "- ALWAYS type stores as KeyValueStore<K,V> from agentNode.getStore(), never as HashMap or Map.\n"
    + "- KeyValueStore import is ALWAYS 'import com.rpl.agentorama.store.KeyValueStore' — never com.rpl.rama.core or any other package.\n"
    + "- AgentNode import is ALWAYS 'import com.rpl.agentorama.AgentNode' — never com.rpl.agentorama.core or any other package.\n"
    + "- ALL Agent-o-rama classes import from 'com.rpl.agentorama.*' — never from com.rpl.agentorama.core, com.rpl.rama.core, or any subpackage unless explicitly stated.\n"
    + "- When a node can emit to multiple targets, ALWAYS declare them as the second parameter: .node(\"route\", List.of(\"targetA\", \"targetB\"), (AgentNode agentNode, Type input) -> {...}). Never use null when multiple emit targets exist.\n"
    + "- ALWAYS use 'extends AgentModule', never 'implements AgentModule'.\n"
    + "- The method signature is 'protected void defineAgents(AgentTopology topology)' — "
    + "never 'declareAgents', never 'Topology' as the parameter type.\n"
    + "- The correct import is 'com.rpl.agentorama.AgentTopology' not 'com.rpl.agentorama.Topology'.\n"
    + "- Use only classes available in Agent-o-rama 0.9.0, Rama 1.8.0, "
    + "LangChain4j 1.4.0, and the JDK.";

  @Override
  protected void defineAgents(AgentTopology topology) {

    // ---- Agent objects -----------------------------------------------------
    topology.declareAgentObject("anthropic-api-key", System.getenv("ANTHROPIC_API_KEY"));

    // Generator model — Anthropic, same provider that already ran in
    // StarmanModule. Mirror StarmanModule's builder exactly (same model name)
    // so the proven wiring carries over. Gemini swap later = this builder only.
    topology.declareAgentObjectBuilder("generator-model", setup -> {
      String key = (String) setup.getAgentObject("anthropic-api-key");
      return AnthropicChatModel.builder()
          .apiKey(key)
          .modelName(generatorModelName())
          .maxTokens(4096)
          // Anthropic prompt caching: caches the SystemMessage prefix so
          // revision-loop retries pay ~10% for those tokens. Only engages
          // once the prefix exceeds the model's minimum (~2048 tokens for
          // Haiku) — i.e., when $$accepted-patterns grows. If this method
          // isn't in langchain4j-anthropic 1.4.0, javac will say so —
          // delete the line and we verify the right API on chat-o-rama.
          .cacheSystemMessages(true)
          .build();
    });

    // ---- Stores ------------------------------------------------------------
    // $$system-prompt: key "current" → the live generator system prompt.
    // The future PromptAgent proposes replacements here.
    topology.declareKeyValueStore("$$system-prompt", String.class, String.class);

    // $$accepted-patterns: key "all" → ArrayList of pattern strings injected
    // into the generation prompt as verified examples.
    topology.declareKeyValueStore("$$accepted-patterns", String.class, List.class);

    // $$codegen-attempts: attemptId → attempt record. SEAM: replace with a
    // *codegen-attempts depot append when event-sourced history is needed.
    topology.declareKeyValueStore("$$codegen-attempts", String.class, Map.class);

    // ---- Agent graph -------------------------------------------------------
    topology.newAgent("CodingAgent")

      .node("generate", "compile-validate",
          (AgentNode agentNode, Map<String, Object> req) -> {

        KeyValueStore<String, String> promptStore = agentNode.getStore("$$system-prompt");
        String systemPrompt = promptStore.get("current");
        if (systemPrompt == null) {
          systemPrompt = DEFAULT_SYSTEM_PROMPT;
          promptStore.put("current", systemPrompt);
        }

        KeyValueStore<String, List> patternStore = agentNode.getStore("$$accepted-patterns");
        List patterns = patternStore.get("all");

        String task = (String) req.get("task");
        int revision = (int) req.getOrDefault("revision", 0);
        String priorCode = (String) req.get("code");
        String priorErrors = (String) req.get("errors");

        StringBuilder user = new StringBuilder();
        user.append("TASK:\n").append(task).append("\n");
        if (patterns != null && !patterns.isEmpty()) {
          user.append("\nVERIFIED PATTERNS (follow these conventions):\n");
          for (Object p : patterns) user.append(p).append("\n---\n");
        }
        if (revision > 0) {
          user.append("\nYour previous attempt FAILED to compile.\n")
              .append("PREVIOUS CODE:\n").append(priorCode).append("\n")
              .append("COMPILER ERRORS:\n").append(priorErrors).append("\n")
              .append("Fix every error. Output the complete corrected file.");
        }

        ChatModel model = agentNode.getAgentObject("generator-model");
        // System prompt travels as a real SystemMessage — required for the
        // API to treat it as a cacheable stable prefix across calls.
        ChatResponse chatResponse = model.chat(
            SystemMessage.from(systemPrompt),
            UserMessage.from(user.toString()));
        String response = chatResponse.aiMessage().text();

        String code = extractJavaCode(response);

        Map<String, Object> next = new HashMap<>(req);
        next.put("revision", revision);
        next.put("code", code);
        agentNode.emit("compile-validate", next);
      })

      .node("compile-validate", List.of("accept", "surface", "generate"),
          (AgentNode agentNode, Map<String, Object> req) -> {

        String code = (String) req.get("code");
        int revision = (int) req.get("revision");
        String taskId = (String) req.get("taskId");

        Map<String, Object> compileResult = compileInMemory(code);
        boolean passed = (boolean) compileResult.get("success");
        String errors = (String) compileResult.get("errors");

        recordAttempt(agentNode, taskId, revision, code, passed, errors);

        if (passed) {
          agentNode.emit("accept", req);
        } else if (revision + 1 < MAX_REVISIONS) {
          Map<String, Object> retry = new HashMap<>(req);
          retry.put("revision", revision + 1);
          retry.put("errors", errors);
          agentNode.emit("generate", retry);
        } else {
          Map<String, Object> failed = new HashMap<>(req);
          failed.put("errors", errors);
          agentNode.emit("surface", failed);
        }
      })

      .node("accept", null, (AgentNode agentNode, Map<String, Object> req) -> {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "accepted");
        result.put("taskId", req.get("taskId"));
        result.put("attempts", ((int) req.get("revision")) + 1);
        result.put("code", req.get("code"));
        agentNode.result(result);
      })

      .node("surface", null, (AgentNode agentNode, Map<String, Object> req) -> {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "needs-human");
        result.put("taskId", req.get("taskId"));
        result.put("attempts", ((int) req.get("revision")) + 1);
        result.put("code", req.get("code"));
        result.put("errors", req.get("errors"));
        agentNode.result(result);
      });
  }

  // ---- Helpers -------------------------------------------------------------

  /**
   * One place for the generator model name. Set ANTHROPIC_MODEL_NAME to match
   * whatever StarmanModule ran with; the default below is a solid codegen
   * model. For cheap iteration on the loop mechanics, a Haiku-class model
   * keeps token costs low while you watch the revision cycle behave.
   */
  static String generatorModelName() {
    return System.getenv().getOrDefault("ANTHROPIC_MODEL_NAME", "claude-haiku-4-5");
  }

  /** SEAM for event sourcing: swap body for a depot append later. */
  private static void recordAttempt(AgentNode agentNode, String taskId, int revision,
                                    String code, boolean passed, String errors) {
    KeyValueStore<String, Map> attempts = agentNode.getStore("$$codegen-attempts");
    Map<String, Object> rec = new HashMap<>();
    rec.put("taskId", taskId);
    rec.put("revision", revision);
    rec.put("model", generatorModelName());  // failures are model-specific —
                                             // history must say who wrote it
    rec.put("code", code);
    rec.put("passed", passed);
    rec.put("errors", errors);
    rec.put("timestamp", System.currentTimeMillis());
    attempts.put(taskId + "-r" + revision, rec);
  }

  /** Pulls the first ```java fenced block, or returns the raw text if unfenced. */
  static String extractJavaCode(String response) {
    Matcher m = Pattern.compile("```(?:java)?\\s*([\\s\\S]*?)```").matcher(response);
    if (m.find()) return m.group(1).trim();
    return response.trim();
  }

  /**
   * Ground-truth compile via in-process javac against this JVM's classpath —
   * the same AOR/Rama/LangChain4j jars Maven resolved for the running cluster.
   *
   * Caveat: under `mvn exec:java` the java.class.path property may be a
   * launcher stub. Override with env STARMAN_COMPILE_CLASSPATH, generated via:
   *   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
   * From IntelliJ run configs the property is the full classpath and works as-is.
   */
  static Map<String, Object> compileInMemory(String source) {
    Map<String, Object> out = new HashMap<>();
    try {
      String className = extractPublicClassName(source);
      if (className == null) {
        out.put("success", false);
        out.put("errors", "No public top-level class found in generated source.");
        return out;
      }
      String pkg = extractPackage(source);

      Path tmpDir = Files.createTempDirectory("starman-codegen");
      Path srcDir = tmpDir;
      if (pkg != null) {
        srcDir = tmpDir.resolve(pkg.replace('.', File.separatorChar));
        Files.createDirectories(srcDir);
      }
      Path srcFile = srcDir.resolve(className + ".java");
      Files.writeString(srcFile, source);

      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
      String classpath = System.getenv("STARMAN_COMPILE_CLASSPATH");
      if (classpath == null) classpath = System.getProperty("java.class.path");

      try (StandardJavaFileManager fm =
               compiler.getStandardFileManager(diagnostics, null, null)) {
        Iterable<? extends JavaFileObject> units =
            fm.getJavaFileObjectsFromFiles(Arrays.asList(srcFile.toFile()));
        List<String> options = new ArrayList<>(Arrays.asList(
            "-classpath", classpath,
            "-d", tmpDir.toString(),
            "-proc:none"));
        boolean success = compiler.getTask(
            null, fm, diagnostics, options, null, units).call();

        StringBuilder errs = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
          if (d.getKind() == Diagnostic.Kind.ERROR) {
            errs.append("Line ").append(d.getLineNumber()).append(": ")
                .append(d.getMessage(null)).append("\n");
          }
        }
        out.put("success", success);
        out.put("errors", errs.toString());
      }
    } catch (Exception e) {
      out.put("success", false);
      out.put("errors", "Compile harness exception: " + e);
    }
    return out;
  }

  static String extractPublicClassName(String source) {
    Matcher m = Pattern.compile(
        "public\\s+(?:final\\s+|abstract\\s+)?(?:class|interface|enum|record)\\s+(\\w+)")
        .matcher(source);
    return m.find() ? m.group(1) : null;
  }

  static String extractPackage(String source) {
    Matcher m = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE)
        .matcher(source);
    return m.find() ? m.group(1) : null;
  }
}
