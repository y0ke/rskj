/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package co.rsk.net.discovery;

import co.rsk.net.discovery.message.*;
import co.rsk.net.discovery.table.NodeDistanceTable;
import co.rsk.net.discovery.table.OperationResult;
import co.rsk.net.discovery.table.PeerDiscoveryRequestBuilder;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.ethereum.crypto.ECKey;
import org.ethereum.db.ByteArrayWrapper;
import org.ethereum.net.rlpx.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Created by mario on 10/02/17.
 */
public class PeerExplorer {

    private static final Logger logger = LoggerFactory.getLogger(PeerExplorer.class);
    public static final int MAX_NODES_PER_MSG = 20;
    public static final int MAX_NODES_TO_ASK = 24;
    public static final int MAX_NODES_TO_CHECK = 16;

    private Set<InetSocketAddress> bootNodes = new ConcurrentHashSet<>();
    private Map<String, PeerDiscoveryRequest> pendingPingRequests = new ConcurrentHashMap<>();
    private Map<String, PeerDiscoveryRequest> pendingFindNodeRequests = new ConcurrentHashMap<>();
    private Map<String, Node> nodesAsked = new ConcurrentHashMap<>();

    private Map<ByteArrayWrapper, Node> establishedConnections = new ConcurrentHashMap<>();

    private UDPChannel udpChannel;

    private ECKey key;

    private Node localNode;

    private NodeDistanceTable distanceTable;

    private PeerExplorerCleaner cleaner;

    private NodeChallengeManager challengeManager;

    private long requestTimeout;

    public PeerExplorer(List<String> initialBootNodes, Node localNode, NodeDistanceTable distanceTable, ECKey key, long reqTimeOut, long refreshPeriod) {
        this.localNode = localNode;
        this.key = key;
        this.distanceTable = distanceTable;

        loadInitialBootNodes(initialBootNodes);

        this.cleaner = new PeerExplorerCleaner(this, refreshPeriod);
        this.challengeManager = new NodeChallengeManager();
        this.requestTimeout = reqTimeOut;
    }

    public void start() {
        this.cleaner.run();
        this.startConversationWithNewNodes();
    }

    public Set<String> startConversationWithNewNodes() {
        Set<String> sentAddresses = new HashSet<>();
        for (InetSocketAddress nodeAddress : this.bootNodes) {
            sendPing(nodeAddress, 1);
            sentAddresses.add(nodeAddress.toString());
        }
        this.bootNodes.removeAll(pendingPingRequests.values().stream()
                .map(PeerDiscoveryRequest::getAddress).collect(Collectors.toList()));
        return sentAddresses;
    }

    public void setUDPChannel(UDPChannel udpChannel) {
        this.udpChannel = udpChannel;
    }


    public void handleMessage(DiscoveryEvent event) {
        DiscoveryMessageType type = event.getMessage().getMessageType();
        if (type == DiscoveryMessageType.PING)
            this.handlePingMessage(event.getAddress(), (PingPeerMessage) event.getMessage());

        if (type == DiscoveryMessageType.PONG)
            this.handlePong(event.getAddress(), (PongPeerMessage) event.getMessage());

        if (type == DiscoveryMessageType.FIND_NODE)
            this.handleFindNode(event.getAddress(), (FindNodePeerMessage) event.getMessage());

        if (type == DiscoveryMessageType.NEIGHBORS)
            this.handleNeighborsMessage(event.getAddress(), (NeighborsPeerMessage) event.getMessage());
    }

    public void handlePingMessage(InetSocketAddress incomingAddress, PingPeerMessage message) {
        this.sendPong(incomingAddress, message.getMessageId());
        Node connectedNode = this.establishedConnections.get(new ByteArrayWrapper(message.getNodeId()));
        if (connectedNode == null) {
            this.sendPing(incomingAddress, 1);
        } else {
            this.distanceTable.updateEntry(connectedNode);
        }
    }

    public void handlePong(InetSocketAddress incomingAddress, PongPeerMessage message) {
        PeerDiscoveryRequest request = this.pendingPingRequests.get(message.getMessageId());
        if (request != null && request.validateMessageResponse(incomingAddress, message)) {
            this.pendingPingRequests.remove(message.getMessageId());
            NodeChallenge challenge = this.challengeManager.removeChallenge(message.getMessageId());
            if (challenge == null)
                this.addConnection(message, incomingAddress);
        }
    }

    public void handleFindNode(InetSocketAddress incomingAddress, FindNodePeerMessage message) {
        Node connectedNode = this.establishedConnections.get(new ByteArrayWrapper(message.getNodeId()));
        if (connectedNode != null) {
            List<Node> nodesToSend = this.distanceTable.getClosestNodes(message.getNodeId());
            this.sendNeighbors(incomingAddress, nodesToSend, message.getMessageId());
            this.distanceTable.updateEntry(connectedNode);
        }
    }

    public void handleNeighborsMessage(InetSocketAddress incomingAddress, NeighborsPeerMessage message) {
        Node connectedNode = this.establishedConnections.get(new ByteArrayWrapper(message.getNodeId()));
        if (connectedNode != null) {
            PeerDiscoveryRequest request = this.pendingFindNodeRequests.get(message.getMessageId());
            if (request != null && request.validateMessageResponse(incomingAddress, message)) {
                List<Node> nodes = (message.countNodes() > MAX_NODES_PER_MSG) ? message.getNodes().subList(0, MAX_NODES_PER_MSG -1) : message.getNodes();

                nodes.stream().filter(n -> !StringUtils.equals(n.getHexId(), this.localNode.getHexId()))
                        .forEach(node -> this.bootNodes.add(node.getAddress()));

                this.startConversationWithNewNodes();
            }
            this.distanceTable.updateEntry(connectedNode);
        }
    }

    public List<Node> getNodes() {
        return  new ArrayList<>(this.establishedConnections.values());
    }

    public PingPeerMessage sendPing(InetSocketAddress nodeAddress, int attempt) {
        return sendPing(nodeAddress, attempt, null);
    }

    public PingPeerMessage sendPing(InetSocketAddress nodeAddress, int attempt, Node node) {
        PingPeerMessage nodeMessage = checkPendingPeerToAddress(nodeAddress);
        if(nodeMessage != null) {
            return nodeMessage;
        }
        InetSocketAddress localAddress = this.localNode.getAddress();
        String id = UUID.randomUUID().toString();
        nodeMessage = PingPeerMessage.create(localAddress.getHostName(), localAddress.getPort(), id, this.key);
        udpChannel.write(new DiscoveryEvent(nodeMessage, nodeAddress));

        PeerDiscoveryRequest request = PeerDiscoveryRequestBuilder.builder().messageId(id)
                .message(nodeMessage).address(nodeAddress).expectedResponse(DiscoveryMessageType.PONG).relatedNode(node)
                .expirationPeriod(requestTimeout).attemptNumber(attempt).build();

        pendingPingRequests.put(nodeMessage.getMessageId(), request);
        return nodeMessage;
    }

    private PingPeerMessage checkPendingPeerToAddress(InetSocketAddress address) {
        for(PeerDiscoveryRequest req : this.pendingPingRequests.values()) {
            if(req.getAddress().equals(address)) {
                return (PingPeerMessage) req.getMessage();
            }
        }
        return null;
    }

    public PongPeerMessage sendPong(InetSocketAddress nodeAddress, String id) {
        InetSocketAddress localAddress = this.localNode.getAddress();
        PongPeerMessage pongPeerMessage = PongPeerMessage.create(localAddress.getHostName(), localAddress.getPort(), id, this.key);
        udpChannel.write(new DiscoveryEvent(pongPeerMessage, nodeAddress));
        return pongPeerMessage;
    }

    public FindNodePeerMessage sendFindNode(Node node) {
        InetSocketAddress nodeAddress = node.getAddress();
        String id = UUID.randomUUID().toString();
        FindNodePeerMessage findNodePeerMessage = FindNodePeerMessage.create(this.key.getNodeId(), id, this.key);
        udpChannel.write(new DiscoveryEvent(findNodePeerMessage, nodeAddress));
        PeerDiscoveryRequest request = PeerDiscoveryRequestBuilder.builder().messageId(id).relatedNode(node)
                .message(findNodePeerMessage).address(nodeAddress).expectedResponse(DiscoveryMessageType.NEIGHBORS)
                .expirationPeriod(requestTimeout).build();
        pendingFindNodeRequests.put(findNodePeerMessage.getMessageId(), request);
        this.nodesAsked.put(node.getHexId(), node);
        return findNodePeerMessage;
    }

    public NeighborsPeerMessage sendNeighbors(InetSocketAddress nodeAddress, List<Node> nodes, String id) {
        List<Node> nodesToSend = getRandomizeLimitedList(nodes, MAX_NODES_PER_MSG, 5);
        NeighborsPeerMessage sendNodesMessage = NeighborsPeerMessage.create(nodesToSend, id, this.key);
        udpChannel.write(new DiscoveryEvent(sendNodesMessage, nodeAddress));
        return sendNodesMessage;
    }

    public void purgeRequests() {
        List<PeerDiscoveryRequest> oldPingRequests = this.removeExpiredRequests(this.pendingPingRequests);
        this.resendExpiredPing(oldPingRequests);
        this.removeConnections(oldPingRequests.stream().
                filter(r -> r.getAttemptNumber() >= 3).collect(Collectors.toList()));

        removeExpiredRequests(this.pendingFindNodeRequests);
    }

    public void cleanAndUpdate() {
        List<Node> closestNodes = this.distanceTable.getClosestNodes(this.localNode.getId());
        this.askForMoreNodes(closestNodes);
        this.checkPeersPulse(closestNodes);

        this.purgeRequests();
    }

    private void checkPeersPulse(List<Node> closestNodes) {
        List<Node> nodesToCheck = this.getRandomizeLimitedList(closestNodes, MAX_NODES_TO_CHECK, 10);
        nodesToCheck.forEach(node -> sendPing(node.getAddress(), 1, node));
    }

    private void askForMoreNodes(List<Node> closestNodes) {
        List<Node> nodesNotQueriedYet = closestNodes.stream().filter(n -> !this.nodesAsked.containsKey(n.getHexId()))
                .collect(Collectors.toList());
        List<Node> nodesToAsk = getRandomizeLimitedList(nodesNotQueriedYet, MAX_NODES_TO_ASK, 5);
        nodesToAsk.forEach(this::sendFindNode);
    }

    private List<PeerDiscoveryRequest> removeExpiredRequests(Map<String, PeerDiscoveryRequest> pendingRequests) {
        List<PeerDiscoveryRequest> requests = pendingRequests.values().stream()
                .filter(PeerDiscoveryRequest::hasExpired).collect(Collectors.toList());
        requests.forEach(r -> pendingRequests.remove(r.getMessageId()));
        return requests;
    }

    private void resendExpiredPing(List<PeerDiscoveryRequest> peerDiscoveryRequests) {
        peerDiscoveryRequests.stream().filter(r -> r.getAttemptNumber() < 3)
                .forEach(r -> sendPing(r.getAddress(), r.getAttemptNumber() + 1));
    }

    private void removeConnections(List<PeerDiscoveryRequest> expiredRequests) {
        if (CollectionUtils.isNotEmpty(expiredRequests)) {
            for (PeerDiscoveryRequest req : expiredRequests) {
                Node node = req.getRelatedNode();
                if (node != null) {
                    this.establishedConnections.remove(new ByteArrayWrapper(node.getId()));
                    this.distanceTable.removeNode(node);
                }
            }
        }
    }

    private void addConnection(PongPeerMessage message, InetSocketAddress incommingAddress) {
        Node senderNode = new Node(message.getNodeId(), incommingAddress.getHostName(), incommingAddress.getPort());
        if (!StringUtils.equals(senderNode.getHexId(), this.localNode.getHexId())) {
            OperationResult result = this.distanceTable.addNode(senderNode);
            if (result.isSuccess()) {
                ByteArrayWrapper senderId = new ByteArrayWrapper(senderNode.getId());
                this.establishedConnections.put(senderId, senderNode);
            } else {
                this.challengeManager.startChallenge(result.getAffectedEntry().getNode(), senderNode, this);
            }
        }
    }

    private void loadInitialBootNodes(List<String> nodes) {
        if (CollectionUtils.isNotEmpty(nodes)) {
            for (String node : nodes) {
                String[] addressData = StringUtils.split(node, ":");
                if (addressData != null && addressData.length == 2) {
                    List<String> dataList = Arrays.asList(addressData);
                    bootNodes.add(new InetSocketAddress(dataList.get(0), Integer.parseInt(dataList.get(1))));
                } else {
                    logger.debug("Invalid bootNode address: {}", node);
                }
            }
        }
    }

    private List<Node> getRandomizeLimitedList(List<Node> nodes, int maxNumber, int randomElements) {
        if(CollectionUtils.size(nodes) <= maxNumber) {
            return nodes;
        } else {
            List<Node> ret = new ArrayList<>();
            int limit = maxNumber - randomElements;
            ret.addAll(nodes.subList(0, limit - 1));
            ret.addAll(collectRandomNodes(nodes.subList(limit, nodes.size()), randomElements));
            return ret;
        }
    }

    private Set<Node> collectRandomNodes(List<Node> originalList, int elementsNbr) {
        Set<Node> ret = new HashSet<>();
        SecureRandom rnd = new SecureRandom();
        while (ret.size() < elementsNbr) {
            int i = rnd.nextInt(originalList.size());
            ret.add(originalList.get(i));
        }
        return ret;
    }

}
