# Verified Rama 1.8.0 / AOR 0.9.0 API Corrections
Last updated: Jun 10 2026 — verified by jar inspection

## Rama 1.8.0

- `Topologies` is a nested class — use `RamaModule.Topologies` as the method parameter type, no separate import needed
- `Ops` lives in `com.rpl.rama.ops.Ops` not `com.rpl.rama.helpers.Ops`
- Correct import: `import com.rpl.rama.ops.Ops;`
- Correct depot declaration: `setup.declareDepot("*name", Depot.hashBy(Ops.FIRST))`
- Correct depot partitioning import: `import com.rpl.rama.Depot;`

## AOR 0.9.0

- `AgentNode` import: `import com.rpl.agentorama.AgentNode;`
- `AgentTopology` import: `import com.rpl.agentorama.AgentTopology;`
- All AOR classes import from `com.rpl.agentorama.*` never from subpackages
- Agent definition: `topology.newAgent("Name").node(...)` never `topology.agent(...)`
- Module base class: `extends AgentModule` never `implements AgentModule`
- Method signature: `protected void defineAgents(AgentTopology topology)`
- Node lambdas require explicit types: `(AgentNode agentNode, String input)` never `(agentNode, input)`
- KeyValueStore import: `import com.rpl.agentorama.store.KeyValueStore;`
- Store declaration before agent: `topology.declareKeyValueStore("$$name", KeyClass.class, ValueClass.class)`