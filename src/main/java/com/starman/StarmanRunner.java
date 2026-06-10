package com.starman;

import com.rpl.agentorama.AgentClient;
import com.rpl.agentorama.AgentManager;
import com.rpl.rama.test.InProcessCluster;
import com.rpl.rama.test.LaunchConfig;

import java.util.HashMap;
import java.util.Map;

public class StarmanRunner {

    public static void main(String[] args) throws Exception {

        System.out.println("=== Project Starman - Starting up ===");

        try (InProcessCluster ipc = InProcessCluster.create()) {

            StarmanModule module = new StarmanModule();
            ipc.launchModule(module, new LaunchConfig(1, 1));
            String moduleName = module.getModuleName();

            AgentManager manager = AgentManager.create(ipc, moduleName);
            AgentClient starman = manager.getAgentClient("StarmanAgent");

            System.out.println("Module launched. Running test turns...");

            Map<String, Object> turn1 = new HashMap<>();
            turn1.put("sessionId", "test-session-001");
            turn1.put("userMessage", "Hey, what can you help me with?");
            Object result1 = starman.invoke(turn1);
            System.out.println("Starman: " + result1);

            Thread.sleep(2000);

            Map<String, Object> turn2 = new HashMap<>();
            turn2.put("sessionId", "test-session-001");
            turn2.put("userMessage", "I am learning to build in Java with Agent-o-rama.");
            Object result2 = starman.invoke(turn2);
            System.out.println("Starman: " + result2);

            Thread.sleep(2000);

            Map<String, Object> turn3 = new HashMap<>();
            turn3.put("sessionId", "test-session-001");
            turn3.put("userMessage", "What did I just tell you?");
            Object result3 = starman.invoke(turn3);
            System.out.println("Starman: " + result3);
        }

        System.out.println("=== Starman shutdown complete ===");
    }
}