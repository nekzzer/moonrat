export function createStore(initial) {
  let state = { ...initial };
  const subscribers = new Set();

  const get = () => state;

  const update = (patch) => {
    state = { ...state, ...(typeof patch === "function" ? patch(state) : patch) };
    subscribers.forEach((subscriber) => subscriber(state));
    return state;
  };

  const subscribe = (subscriber) => {
    subscribers.add(subscriber);
    subscriber(state);
    return () => subscribers.delete(subscriber);
  };

  return { get, update, subscribe };
}
