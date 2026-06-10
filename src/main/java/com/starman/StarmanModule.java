package com.starman;

import com.rpl.agentorama.AgentModule;
import com.rpl.agentorama.AgentNode;
import com.rpl.agentorama.AgentTopology;
import com.rpl.agentorama.store.KeyValueStore;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StarmanModule extends AgentModule {

    @Override
    protected void defineAgents(AgentTopology topology) {

        topology.declareKeyValueStore("$$session-context", String.class, Map.class);

        topology.declareAgentObject("anthropic-api-key", System.getenv("ANTHROPIC_API_KEY"));

        topology.declareAgentObjectBuilder("fast-model", setup -> {
            String key = (String) setup.getAgentObject("anthropic-api-key");
            return AnthropicChatModel.builder()
                    .apiKey(key)
                    .modelName("claude-haiku-4-5")
                    .build();
        });

        topology.newAgent("StarmanAgent")
            .node("entry-node", "fast-response-node",
                (AgentNode agentNode, Map<String, Object> input) -> {
                    agentNode.emit("fast-response-node", input);
                })
            .node("fast-response-node", null,
                (AgentNode agentNode, Map<String, Object> input) -> {
                    String sessionId = (String) input.get("sessionId");
                    String userMessage = (String) input.get("userMessage");

                    @SuppressWarnings("unchecked")
                    KeyValueStore<String, Map<String, Object>> store =
                        agentNode.getStore("$$session-context");

                    Map<String, Object> ctx = store.get(sessionId);
                    if (ctx == null) {
                        ctx = new HashMap<>();
                        ctx.put("history", new ArrayList<String>());
                    }

                    @SuppressWarnings("unchecked")
                    List<String> history = (List<String>) ctx.get("history");

                    StringBuilder prompt = new StringBuilder();
                    prompt.append("You are Starman, a personal assistant.\n");
                    if (!history.isEmpty()) {
                        prompt.append("Recent context: ");
                        int start = Math.max(0, history.size() - 3);
                        for (int i = start; i < history.size(); i++) {
                            prompt.append(history.get(i)).append(" ");
                        }
                        prompt.append("\n");
                    }
                    prompt.append("User: ").append(userMessage);

                    ChatModel model =
                        (ChatModel) agentNode.getAgentObject("fast-model");
                    String response = model.chat(prompt.toString());

                    history.add("User: " + userMessage);
                    history.add("Starman: " + response);
                    store.put(sessionId, ctx);

                    agentNode.result(response);
                });
    }
}