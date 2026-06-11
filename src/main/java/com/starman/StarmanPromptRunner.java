package com.starman;

import com.rpl.agentorama.AgentClient;
import com.rpl.agentorama.AgentManager;
import com.rpl.agentorama.UI;
import com.rpl.rama.PState;
import com.rpl.rama.Path;
import com.rpl.rama.test.InProcessCluster;
import com.rpl.rama.test.LaunchConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * PromptAgent runner.
 *
 * 1. Launches StarmanCodingModule (IPC)
 * 2. Runs several codegen tasks to populate *codegen-attempts depot
 * 3. Queries $$violation-patterns PState for top errors
 * 4. Invokes PromptAgent with the violation summary
 * 5. Prints the proposed new rule to console for human review
 *
 * Human applies the rule manually via Claude Code if approved.
 */
public class StarmanPromptRunner {

  public static void main(String[] args) throws Exception {
    try (InProcessCluster ipc = InProcessCluster.create();
         AutoCloseable ui = UI.start(ipc)) {

      StarmanCodingModule module = new StarmanCodingModule();
      ipc.launchModule(module, new LaunchConfig(1, 1));
      String moduleName = module.getModuleName();

      AgentManager manager = AgentManager.create(ipc, moduleName);
      AgentClient codingAgent = manager.getAgentClient("CodingAgent");
      AgentClient promptAgent = manager.getAgentClient("PromptAgent");

      // ── Step 1: Run codegen tasks to populate attempt history ─────────
      System.out.println("=== Running codegen tasks to build violation history ===");

      String[] tasks = {
        "Write an Agent-o-rama AgentModule named CounterModule in package "
        + "com.starman.generated. Declares a KeyValueStore $$counts with "
        + "String keys and Integer values. One agent CounterAgent with one "
        + "node increment that reads the count for key from input String, "
        + "adds 1, writes it back, returns the new count as Integer. "
        + "No LLM calls. Include all imports.",

        "Write an Agent-o-rama AgentModule named GreeterModule in package "
        + "com.starman.generated. No stores, no LLM. One agent GreeterAgent "
        + "with one node greet that takes a String name and calls "
        + "agentNode.result() with Hello + name. Include all imports.",

        "Write an Agent-o-rama AgentModule named TimestampModule in package "
        + "com.starman.generated. No stores, no LLM. One agent TimestampAgent "
        + "with one node stamp that takes a String label and calls "
        + "agentNode.result() with label + : + System.currentTimeMillis(). "
        + "Include all imports."
      };

      for (int i = 0; i < tasks.length; i++) {
        Map<String, Object> req = new HashMap<>();
        req.put("taskId", "prompt-seed-" + i);
        req.put("revision", 0);
        req.put("task", tasks[i]);

        System.out.println("Task " + (i + 1) + "/" + tasks.length + "...");
        Map<String, Object> result = (Map<String, Object>) codingAgent.invoke(req);
        System.out.println("  Status: " + result.get("status")
            + "  Attempts: " + result.get("attempts"));
      }

      // ── Step 2: Query $$violation-patterns PState ─────────────────────
      System.out.println("\n=== Querying violation patterns ===");

      PState violationPatterns = ipc.clusterPState(moduleName, "$$violation-patterns");

      // Select all entries — returns the full map
      Map<String, Long> patterns =
          (Map<String, Long>) violationPatterns.selectOne(Path.stay());

      if (patterns == null || patterns.isEmpty()) {
        System.out.println("No violations recorded — all tasks passed first attempt.");
        System.out.println("Run more tasks or introduce a harder task to seed violations.");
        System.in.read();
        return;
      }

      // Format summary for PromptAgent
      StringBuilder summary = new StringBuilder();
      patterns.entrySet().stream()
          .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
          .limit(5)
          .forEach(e -> summary.append(e.getValue())
              .append("x: ").append(e.getKey()).append("\n"));

      System.out.println("Top violations:\n" + summary);

      // ── Step 3: Invoke PromptAgent ────────────────────────────────────
      System.out.println("=== Invoking PromptAgent ===");

      Map<String, Object> promptReq = new HashMap<>();
      promptReq.put("violationSummary", summary.toString());

      Map<String, Object> proposal =
          (Map<String, Object>) promptAgent.invoke(promptReq);

      // ── Step 4: Print proposal for human review ───────────────────────
      System.out.println("\n╔══════════════════════════════════════════╗");
      System.out.println("║         PROMPT AGENT PROPOSAL            ║");
      System.out.println("╚══════════════════════════════════════════╝");
      System.out.println("\nViolations analyzed:\n" + proposal.get("violationSummary"));
      System.out.println("Proposed new rule:");
      System.out.println("  >> " + proposal.get("proposedRule"));
      System.out.println("\nTo apply: add the rule above to DEFAULT_SYSTEM_PROMPT");
      System.out.println("in StarmanCodingModule.java via Claude Code.");
      System.out.println("\nWeb UI at http://localhost:1974 — press Enter to exit.");
      System.in.read();
    }
  }
}
