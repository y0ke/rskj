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

package co.rsk.net.simples;

import co.rsk.net.MessageSender;
import co.rsk.net.NodeID;
import co.rsk.net.messages.GetBlockMessage;
import co.rsk.net.messages.Message;
import co.rsk.net.messages.MessageType;
import org.ethereum.db.ByteArrayWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by ajlopez on 5/11/2016.
 */
public class SimpleMessageSender implements MessageSender {
    private List<Message> messages = new ArrayList<>();
    private NodeID nodeID = new NodeID(new byte[]{});

    public void sendMessage(Message message) {
        this.messages.add(message);
    }

    public List<Message> getMessages() {
        return this.messages;
    }

    public List<Message> getGetBlockMessages() {
        List<Message> result = new ArrayList<>();

        for (Message message : this.messages)
            if (message.getMessageType() == MessageType.GET_BLOCK_MESSAGE)
                result.add(message);

        return result;
    }

    public List<ByteArrayWrapper> getGetBlockMessagesHashes() {
        List<ByteArrayWrapper> result = new ArrayList<>();

        for (Message message : this.messages)
            if (message.getMessageType() == MessageType.GET_BLOCK_MESSAGE)
                result.add(new ByteArrayWrapper(((GetBlockMessage)message).getBlockHash()));

        return result;
    }

    public NodeID getNodeID() {
        return nodeID;
    }

    @Override
    public void setNodeID(byte[] nodeID) {
        this.nodeID = new NodeID(nodeID);
    }
}
