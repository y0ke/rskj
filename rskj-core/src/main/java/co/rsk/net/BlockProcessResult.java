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

package co.rsk.net;

import org.ethereum.core.Block;
import org.ethereum.core.ImportResult;
import org.ethereum.db.ByteArrayWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Created by mario on 07/02/17.
 */
public class BlockProcessResult {

    private static final Logger logger = LoggerFactory.getLogger("messagehandler");

    private boolean additionalValidationsOk = false;

    private Map<ByteArrayWrapper, ImportResult> result;

    public BlockProcessResult(boolean additionalValidations, Map<ByteArrayWrapper, ImportResult> result) {
        this.additionalValidationsOk = additionalValidations;
        this.result = result;
    }

    public boolean wasBlockAdded(Block block) {
        return additionalValidationsOk && !result.isEmpty() && importOk(result.get(new ByteArrayWrapper(block.getHash())));
    }

    public static boolean importOk(ImportResult blockResult) {
        return blockResult != null
                && (blockResult == ImportResult.IMPORTED_BEST || blockResult == ImportResult.IMPORTED_NOT_BEST);
    }

    public void logResult(String blockHash, long time) {
        if(result == null || result.isEmpty()) {
            logger.debug("[MESSAGE PROCESS] Block[{}] After[{}] nano, process result. No block connections were made", time, blockHash);
        } else {
            StringBuilder sb = new StringBuilder("[MESSAGE PROCESS] Block[")
                    .append(blockHash).append("] After[").append(time).append("] nano, process result. Connections attempts: ").append(result.size()).append(" | ");

            for(Map.Entry<ByteArrayWrapper, ImportResult> entry : this.result.entrySet()) {
                sb.append(entry.getKey().toString()).append(" - ").append(entry.getValue()).append(" | ");
            }
            logger.debug(sb.toString());
        }
    }
}
