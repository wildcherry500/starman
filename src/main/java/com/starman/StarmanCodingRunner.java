package com.starman;

import com.rpl.agentorama.AgentClient;
import com.rpl.agentorama.AgentManager;
import com.rpl.agentorama.UI;
import com.rpl.rama.test.InProcessCluster;
import com.rpl.rama.test.LaunchConfig;

import java.util.HashMap;
import java.util.Map;

/**
 * Test harness for the Starman Coding Agent spine.
 *
 * Run from IntelliJ (full classpath → in-process javac sees AOR jars) or:
 *   mvn dependency:build-classpath -Dmdep.outputFile=cp.txt
 *   export STARMAN_COMPILE_CLASSPATH=$(cat cp.txt)
 *   mvn exec:java -Dexec.mainClass="com.starman.StarmanCodingRunner"
 *
 * Watch the revision loop live at http://localhost:1974 — each failed compile
 * shows as an emit back to "generate" with the javac errors in the trace.
 */
public class StarmanCodingRunner {

  public static void main(String[] args) throws Exception {
    try (InProcessCluster ipc = InProcessCluster.create();
         AutoCloseable ui = UI.start(ipc)) {

      StarmanCodingModule module = new StarmanCodingModule();
      ipc.launchModule(module, new LaunchConfig(1, 1));
      String moduleName = module.getModuleName();

      AgentManager manager = AgentManager.create(ipc, moduleName);
      AgentClient agent = manager.getAgentClient("CodingAgent");

      // First real task: small enough to succeed, AOR-specific enough that
      // the compile step genuinely exercises the framework classpath.
      Map<String, Object> request = new HashMap<>();
      request.put("taskId", "task-001");
      request.put("revision", 0);
      request.put("task",
          "Write an Agent-o-rama AgentModule named PriorityRouterModule in package "
        + "com.starman.generated. It defines one agent called \"PriorityRouterAgent\" "
        + "with three nodes. First node named \"route\" declares two possible emit "
        + "targets: \"handle-urgent\" and \"handle-normal\". It takes a String message "
        + "as input. If the message starts with \"URGENT:\" it emits to \"handle-urgent\", "
        + "otherwise emits to \"handle-normal\". Second node \"handle-urgent\" takes a "
        + "String message, calls agentNode.result() with \"[HIGH PRIORITY] \" + message. "
        + "Third node \"handle-normal\" takes a String message, calls agentNode.result() "
        + "with \"[NORMAL] \" + message. No stores, no LLM calls. Include all imports.");

      System.out.println("=== Invoking CodingAgent (task-001) ===");
      long start = System.currentTimeMillis();

      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) agent.invoke(request);

      long elapsed = System.currentTimeMillis() - start;
      System.out.println("Status:   " + result.get("status"));
      System.out.println("Attempts: " + result.get("attempts"));
      System.out.println("Elapsed:  " + elapsed + " ms");
      if ("needs-human".equals(result.get("status"))) {
        System.out.println("--- Compiler errors ---");
        System.out.println(result.get("errors"));
      }
      System.out.println("--- Generated code ---");
      System.out.println(result.get("code"));

      System.out.println("\nWeb UI at http://localhost:1974 — press Enter to exit.");
      System.in.read();
    }
  }
}
