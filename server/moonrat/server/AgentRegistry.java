package moonrat.server;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class AgentRegistry {
    private final Map<String, AgentConnection> agents = new ConcurrentHashMap<>();

    void register(AgentConnection agent) {
        agents.put(agent.id, agent);
    }

    AgentConnection get(String id) {
        return agents.get(id);
    }

    void remove(AgentConnection agent) {
        agents.remove(agent.id, agent);
    }

    boolean isCurrent(AgentConnection agent) {
        return agents.get(agent.id) == agent;
    }

    List<AgentConnection> all() {
        return List.copyOf(agents.values());
    }
}
