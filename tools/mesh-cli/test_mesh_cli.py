import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from mesh_cli import Network, Node, Packet, Policy, RelayStore


def packet(packet_id="p1", ttl=8, max_hops=2, destination="C", payload=b"hello"):
	return Packet(packet_id, "A", destination, payload, ttl, max_hops)


def test_direct_delivery():
	network = Network([Node("A"), Node("C")])
	network.connect("A", "C")

	assert network.send(packet(), Policy()) == "delivered"
	assert "p1" in network.nodes["C"].delivered_packets


def test_mesh_delivery():
	network = Network([Node("A"), Node("B"), Node("C")])
	network.connect("A", "B")
	network.connect("B", "C")

	assert network.send(packet(), Policy()) == "delivered"
	assert network.last_route == ("A", "B", "C")


def test_duplicate_packet_is_ignored():
	network = Network([Node("A"), Node("C")])
	network.connect("A", "C")

	assert network.send(packet(), Policy()) == "delivered"
	assert network.send(packet(), Policy()) == "duplicate"


def test_expired_packet_is_dropped():
	network = Network([Node("A"), Node("B"), Node("C")])
	network.connect("A", "B")
	network.connect("B", "C")

	assert network.send(packet(ttl=1), Policy()) == "expired"


def test_max_hops_is_respected():
	network = Network([Node("A"), Node("B"), Node("C")])
	network.connect("A", "B")
	network.connect("B", "C")

	assert network.send(packet(max_hops=1), Policy(max_hops=1)) == "max_hops"


def test_cyclic_path_is_rejected():
	network = Network([Node("A"), Node("B"), Node("C")])
	network.connect("A", "B")
	network.connect("B", "C")

	cyclic = Packet("cycle", "A", "C", b"x", 8, 2, ("A", "B", "A"))
	assert network.send(cyclic, Policy()) == "cycle"


def test_unavailable_destination_is_relayed():
	network = Network([Node("A"), Node("B"), Node("C", available=False)])
	network.connect("A", "B")
	network.connect("B", "C")

	assert network.send(packet(), Policy()) == "relayed"
	assert network.nodes["B"].relay_store.get("p1") is not None

	network.nodes["C"].available = True
	network.flush_relays()
	assert "p1" in network.nodes["C"].delivered_packets


def test_failed_relay_flush_keeps_packet_for_retry():
	network = Network([Node("A"), Node("B"), Node("C", available=False)])
	network.connect("A", "B")
	network.connect("B", "C")

	assert network.send(packet(), Policy()) == "relayed"
	stored = network.nodes["B"].relay_store.get("p1")
	assert stored.path == ("A", "B")
	assert stored.ttl == 7
	network.disconnect("B", "C")
	network.disconnect("A", "B")
	network.nodes["C"].available = True
	network.flush_relays()
	assert network.nodes["B"].relay_store.get("p1") is not None

	network.connect("B", "C")
	network.flush_relays()
	assert network.nodes["B"].relay_store.get("p1") is None
	assert "p1" in network.nodes["C"].delivered_packets


def test_relay_quota_prunes_oldest_packets():
	store = RelayStore(quota=5)
	store.enqueue(Packet("one", "A", "C", b"1234", 8, 2))
	store.enqueue(Packet("two", "A", "C", b"5678", 8, 2))

	assert store.get("one") is None
	assert store.get("two") is not None


def test_policy_relay_quota_prunes_storage():
	network = Network([Node("A"), Node("B"), Node("C", available=False)])
	network.connect("A", "B")
	network.connect("B", "C")
	policy = Policy(relay_quota=5)

	assert network.send(packet("one", payload=b"1234"), policy) == "relayed"
	assert network.send(packet("two", payload=b"5678"), policy) == "relayed"
	assert network.nodes["B"].relay_store.get("one") is None
	assert network.nodes["B"].relay_store.get("two") is not None


def test_policy_ttl_bounds_packet_lifetime():
	network = Network([Node("A"), Node("B"), Node("C")])
	network.connect("A", "B")
	network.connect("B", "C")

	assert network.send(packet("short-policy", ttl=8), Policy(ttl=1)) == "expired"
	assert network.send(packet("short-packet", ttl=1), Policy(ttl=8)) == "expired"
	assert network.send(packet("short-explicit", ttl=2), Policy(ttl=8)) == "delivered"


def test_speed_policy_prefers_direct_route():
	network = Network([Node("A"), Node("B"), Node("C")])
	network.connect("A", "B")
	network.connect("B", "C")
	network.connect("A", "C")

	assert network.send(packet(), Policy(mode="speed")) == "delivered"
	assert network.last_route == ("A", "C")


def test_coverage_policy_accepts_mesh_route():
	network = Network([Node("A"), Node("B"), Node("C")])
	network.connect("A", "B")
	network.connect("B", "C")
	network.connect("A", "C")

	assert network.send(packet(), Policy(mode="coverage")) == "delivered"
	assert network.last_route == ("A", "B", "C")


def test_available_direct_route_beats_unavailable_mesh_route():
	network = Network([Node("A"), Node("B", available=False), Node("C")])
	network.connect("A", "B")
	network.connect("B", "C")
	network.connect("A", "C")

	assert network.send(packet(), Policy(mode="speed")) == "delivered"
	assert network.last_route == ("A", "C")


def test_cli_module_imports():
	import mesh_cli

	assert mesh_cli is not None
