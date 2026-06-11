package com.starman;

// ── Rama core ────────────────────────────────────────────────────────────────
import com.rpl.rama.Depot;
import com.rpl.rama.PState;
import com.rpl.rama.RamaModule;
import com.rpl.rama.module.StreamTopology;
import com.rpl.rama.ops.Ops;

// ── Agent-o-rama ─────────────────────────────────────────────────────────────
import com.rpl.agentorama.AgentNode;
import com.rpl.agentorama.AgentTopology;
import com.rpl.agentorama.store.KeyValueStore;

// ── LangChain4j ──────────────────────────────────────────────────────────────
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;

// ── javac in-process ─────────────────────────────────────────────────────────
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
 * Starman Coding Agent — Session 3 migration.
 *
 * WHAT CHANGED FROM SESSION 2
 * ───────────────────────────
 * 1. Class declaration: `extends AgentModule` → `implements RamaModule`
 *    Reason: the moment you declare a depot, you need `define(Setup, Topologies)`
 *    and must create AgentTopology explicitly. AgentModule is a convenience
 *    wrapper for pure-agent modules; it cannot co-host a Rama depot.
 *
 * 2. Entry point: `defineAgents(AgentTopology)` → `define(Setup, Topologies)`
 *    AgentTopology is now created explicitly via AgentTopology.create().
 *
 * 3. `$$codegen-attempts` KeyValueStore removed.
 *    Replaced by `*codegen-attempts` depot (Depot.global() — single partition,
 *    no hash complexity until scale demands it per Session 3 decision).
 *
 * 4. `recordAttempt()` seam flipped:
 *    was:  store.put(key, record)
 *    now:  agentNode.getColocatedDepot("*codegen-attempts").append(record)
 *
 * 5. `agentTopology.define()` added as the LAST call in `define()`.
 *    (Silent failure if omitted — agents simply won't be available at runtime.)
 *
 * WHAT IS UNCHANGED
 * ─────────────────
 * - CodingAgent graph: generate → compile-validate → (accept | surface | generate)
 * - MAX_REVISIONS, DEFAULT_SYSTEM_PROMPT, generatorModelName()
 * - $$system-prompt, $$accepted-patterns stores
 * - All compile harness helpers: compileInMemory, extractJavaCode, etc.
 *
 * NEXT STEPPING STONES (not this session)
 * ────────────────────────────────────────
 * B. Stream topology consuming *codegen-attempts → materializes $$violation-patterns PState
 * C. On-demand PromptAgent reading $$violation-patterns, proposing $$system-prompt revisions
 */
public class StarmanCodingModule implements RamaModule {

  static final int MAX_REVISIONS = 3;

  static final String DEFAULT_SYSTEM_PROMPT =
      "You are an expert Java engineer for Red Planet Labs' Agent-o-rama framework. "
    + "Generate a single, complete, compilable Java file. Rules:\n"
    + "- Output ONLY one Java code block, no prose outside it.\n"
    + "- Exactly one public top-level class; the filename will match it.\n"
    + "- Store names must start with $$. Depot names must start with *.\n"
    + "- Every agent graph path must end with agentNode.result(...).\n"
    + "- Use HashMap/ArrayList, never Map.of()/List.of() for stored values.\n"
    + "- Use only classes available in Agent-o-rama 0.9.0, Rama 1.8.0, "
    + "LangChain4j 1.4.0, and the JDK.";

  // ── Required by RamaModule ──────────────────────────────────────────────────
  @Override
  public String getModuleName() { return "StarmanCodingModule"; }

  // ── Main entry point ────────────────────────────────────────────────────────
  @Override
  public void define(RamaModule.Setup setup, RamaModule.Topologies topologies) {

    // ── Step 1: Depot ─────────────────────────────────────────────────────────
    //
    // Single global partition — no hashBy complexity until Session B adds the
    // stream topology that needs co-location guarantees.
    // Depot.Declaration.global() puts all records on one partition and is the
    // correct choice for low-volume, append-only audit logs.
    setup.declareDepot("*codegen-attempts", Depot.hashBy(Ops.FIRST));

    // ── Step 2: Stream topology — materialize $$violation-patterns PState ────
    //
    // Consumes *codegen-attempts depot. For each failed attempt, extracts the
    // first line of errors and increments a counter in $$violation-patterns.
    // Schema: { errorFirstLine<String> : count<Long> }
    StreamTopology violationStream = topologies.stream("violation-projector");

    violationStream.pstate("$$violation-patterns",
        PState.mapSchema(String.class, Long.class));

    violationStream.source("*codegen-attempts").out("*attempt")
        .each((Object attempt) -> {
            Map<String, Object> rec = (Map<String, Object>) attempt;
            Boolean passed = (Boolean) rec.get("passed");
            return passed != null && !passed;
        }, "*attempt").out("*isFailed")
        .keepTrue("*isFailed")
        .each((Object attempt) -> {
            Map<String, Object> rec = (Map<String, Object>) attempt;
            String errors = (String) rec.get("errors");
            return errors == null || errors.isBlank() ? "unknown" : errors;
        }, "*attempt").out("*errorKey")
        .localTransform("$$violation-patterns",
            com.rpl.rama.Path.key("*errorKey").term(count -> count == null ? 1L : (Long) count + 1L));

    // ── Step 3: AgentTopology — must be created AFTER depot declarations ──────
    AgentTopology agentTopology = AgentTopology.create(setup, topologies);

    // ── Step 4: Agent objects ─────────────────────────────────────────────────
    agentTopology.declareAgentObject("anthropic-api-key", System.getenv("ANTHROPIC_API_KEY"));

    agentTopology.declareAgentObjectBuilder("generator-model", s -> {
      String key = (String) s.getAgentObject("anthropic-api-key");
      return AnthropicChatModel.builder()
          .apiKey(key)
          .modelName(generatorModelName())
          .maxTokens(4096)
          .cacheSystemMessages(true)
          .build();
    });

    // ── Step 5: Stores ────────────────────────────────────────────────────────
    //
    // $$codegen-attempts KeyValueStore is GONE — replaced by *codegen-attempts depot above.
    // $$system-prompt and $$accepted-patterns are unchanged.
    agentTopology.declareKeyValueStore("$$system-prompt", String.class, String.class);
    agentTopology.declareKeyValueStore("$$accepted-patterns", String.class, List.class);

    // ── Step 6: Agent graph ───────────────────────────────────────────────────
    agentTopology.newAgent("CodingAgent")

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

        // ── SEAM FLIPPED: was store.put(), now depot append ───────────────────
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

    // ── PromptAgent ───────────────────────────────────────────────────────────
    //
    // On-demand agent. Reads $$violation-patterns, calls Haiku, proposes a new
    // prompt rule. Returns proposal as a String for human review and approval.
    // Human applies the rule manually via Claude Code.
    agentTopology.newAgent("PromptAgent")

        .node("analyze", null,
            (AgentNode agentNode, Map<String, Object> req) -> {

            // Read current system prompt
            KeyValueStore<String, String> promptStore =
                agentNode.getStore("$$system-prompt");
            String currentPrompt = promptStore.get("current");
            if (currentPrompt == null) currentPrompt = DEFAULT_SYSTEM_PROMPT;

            // Build analysis request for Haiku
            String analysisPrompt =
                "You are improving a code generation system prompt.\n\n"
              + "CURRENT SYSTEM PROMPT:\n" + currentPrompt + "\n\n"
              + "TOP VIOLATION PATTERNS (error → count):\n"
              + req.get("violationSummary") + "\n\n"
              + "Propose ONE new rule (one sentence) to add to the system prompt "
              + "that would prevent the most frequent violation. "
              + "Output ONLY the rule text, no explanation, no preamble.";

            ChatModel model = agentNode.getAgentObject("generator-model");
            String proposed = model.chat(analysisPrompt);

            Map<String, Object> result = new HashMap<>();
            result.put("proposedRule", proposed.trim());
            result.put("currentPrompt", currentPrompt);
            result.put("violationSummary", req.get("violationSummary"));
            agentNode.result(result);
        });

    // ── Step 7: CRITICAL — must be the absolute last call ─────────────────────
    // If omitted, agents are silently unavailable at runtime. No error thrown.
    agentTopology.define();
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  static String generatorModelName() {
    return System.getenv().getOrDefault("ANTHROPIC_MODEL_NAME", "claude-haiku-4-5");
  }

  /**
   * Records a codegen attempt by appending to the *codegen-attempts depot.
   *
   * SESSION 3: seam is now live — was store.put(), now depot append.
   * SESSION B: stream topology will consume this depot and materialize
   *            $$violation-patterns PState from the failure history.
   *
   * Uses getColocatedDepot() per ref_03 verified API pattern.
   */
  private static void recordAttempt(AgentNode agentNode, String taskId, int revision,
                                    String code, boolean passed, String errors) {
    Map<String, Object> rec = new HashMap<>();
    rec.put("taskId", taskId);
    rec.put("revision", revision);
    rec.put("model", generatorModelName());
    rec.put("code", code);
    rec.put("passed", passed);
    // Store only the first line of errors — raw javac, no parser yet.
    // This is the field Session B's stream topology will group on for
    // $$violation-patterns. Keeping it trimmed limits PState fan-out.
    rec.put("errors", firstLine(errors));
    rec.put("errorsFull", errors);  // full text preserved for human review
    rec.put("timestamp", System.currentTimeMillis());

    agentNode.getDepot("*codegen-attempts").append(rec);
  }

  /** Returns the first non-blank line of a string, or empty string. */
  static String firstLine(String s) {
    if (s == null || s.isBlank()) return "";
    for (String line : s.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty()) return trimmed;
    }
    return "";
  }

  /** Pulls the first ```java fenced block, or returns raw text if unfenced. */
  static String extractJavaCode(String response) {
    Matcher m = Pattern.compile("```(?:java)?\\s*([\\s\\S]*?)```").matcher(response);
    if (m.find()) return m.group(1).trim();
    return response.trim();
  }

  /**
   * Ground-truth compile via in-process javac against this JVM's classpath.
   *
   * Override with env STARMAN_COMPILE_CLASSPATH if needed:
   *   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
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
