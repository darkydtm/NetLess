"""Platform-independent mesh CLI smoke-test module."""

import argparse
from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True)
class Policy:
	mode: str = "balanced"
	max_hops: int = 2
	ttl: int = 8
	relay_enabled: bool = True
	relay_quota: int = 1024 * 1024


@dataclass(frozen=True)
class Packet:
	packet_id: str
	source: str
	destination: str
	payload: bytes
	ttl: int
	max_hops: int
	path: tuple = ()


class RelayStore:
	def __init__(self, quota=1024 * 1024):
		self.quota = quota
		self._packets = {}

	def enqueue(self, packet):
		self._packets.setdefault(packet.packet_id, packet)
		self.prune()

	def remove(self, packet_id):
		return self._packets.pop(packet_id, None)

	def get(self, packet_id):
		return self._packets.get(packet_id)

	def prune(self, policy=None):
		quota = self.quota if policy is None else policy.relay_quota
		while sum(len(packet.payload) for packet in self._packets.values()) > quota:
			self._packets.pop(next(iter(self._packets)))

	def packets(self):
		return tuple(self._packets.values())


class Node:
	def __init__(self, node_id, available=True, relay_quota=1024 * 1024):
		self.node_id = node_id
		self.available = available
		self.neighbors = set()
		self.relay_store = RelayStore(relay_quota)
		self.delivered_packets = set()
		self.seen_packets = set()


class Network:
	def __init__(self, nodes=()):
		self.nodes = {node.node_id: node for node in nodes}
		self.last_route = ()

	def connect(self, source, destination):
		self.nodes.setdefault(source, Node(source))
		self.nodes.setdefault(destination, Node(destination))
		self.nodes[source].neighbors.add(destination)
		self.nodes[destination].neighbors.add(source)

	def disconnect(self, source, destination):
		self.nodes[source].neighbors.discard(destination)
		self.nodes[destination].neighbors.discard(source)

	def send(self, packet, policy):
		if packet.source not in self.nodes or packet.destination not in self.nodes:
			return "unavailable"
		if any(packet.packet_id in node.delivered_packets for node in self.nodes.values()):
			return "duplicate"
		if packet.ttl <= 0:
			return "expired"
		if len(set(packet.path)) != len(packet.path):
			return "cycle"

		hop_limit = min(packet.max_hops, policy.max_hops)
		route = self._route(packet.source, packet.destination, hop_limit, policy.mode)
		if route is None:
			return "max_hops" if self._route(packet.source, packet.destination, None, policy.mode) else "unavailable"
		self.last_route = route
		current_packet = packet
		for index, node_id in enumerate(route):
			node = self.nodes[node_id]
			if not node.available:
				if policy.relay_enabled and index > 0:
					self.nodes[route[index - 1]].relay_store.enqueue(current_packet)
					return "relayed"
				return "unavailable"
			node.seen_packets.add(current_packet.packet_id)
			if index == len(route) - 1:
				node.delivered_packets.add(current_packet.packet_id)
				return "delivered"
			if current_packet.ttl <= 1:
				return "expired"
			current_packet = Packet(
				current_packet.packet_id,
				current_packet.source,
				current_packet.destination,
				current_packet.payload,
				current_packet.ttl - 1,
				current_packet.max_hops,
				tuple(route[: index + 1]),
			)
		return "unavailable"

	def flush_relays(self):
		for node in self.nodes.values():
			for packet in node.relay_store.packets():
				if self.nodes[packet.destination].available:
					node.relay_store.remove(packet.packet_id)
					self.send(packet, Policy(max_hops=packet.max_hops))

	def _route(self, source, destination, max_hops, mode):
		if source == destination:
			return (source,)
		queue = deque([(source, (source,))])
		routes = []
		while queue:
			node_id, path = queue.popleft()
			if node_id == destination:
				routes.append(path)
				continue
			for neighbor in sorted(self.nodes[node_id].neighbors):
				if neighbor in path or (max_hops is not None and len(path) - 1 >= max_hops):
					continue
				queue.append((neighbor, path + (neighbor,)))
		if not routes:
			return None
		key = len if mode != "coverage" else lambda route: -len(route)
		return min(routes, key=key)


def build_demo_network():
	network = Network(Node(node_id) for node_id in ("A", "B", "C"))
	network.connect("A", "B")
	network.connect("B", "C")
	return network


def main(argv=None):
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("command", choices=("demo", "self-test", "send"))
	parser.add_argument("--source", default="A")
	parser.add_argument("--destination", default="C")
	parser.add_argument("--mode", choices=("speed", "balanced", "coverage"), default="balanced")
	args = parser.parse_args(argv)
	if args.command == "self-test":
		return _self_test()
	network = build_demo_network()
	result = network.send(Packet("demo", args.source, args.destination, b"demo", 8, 2), Policy(args.mode))
	print(f"route={'-'.join(network.last_route)} status={result}")
	return 0


def _self_test():
	network = build_demo_network()
	assert network.send(Packet("self-test", "A", "C", b"ok", 8, 2), Policy()) == "delivered"
	print("self-test: PASS")
	return 0


if __name__ == "__main__":
	main()
