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

package co.rsk.validators;

import org.ethereum.config.SystemProperties;
import org.ethereum.core.Block;
import org.ethereum.core.BlockHeader;
import org.ethereum.vm.DataWord;
import org.junit.Test;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author Angel J Lopez
 * @since 02.23.2016
 */
public class GasLimitRuleTests {
    private GasLimitRule rule = new GasLimitRule(3000000);

    @Test // pass rule
    public void gasLimitGreaterThanMinimumGasLimit() {
        Block block = getBlock(SystemProperties.CONFIG.getBlockchainConfig().getCommonConstants().getMIN_GAS_LIMIT() + 1);
        assertTrue(rule.isValid(block));
    }

    @Test // pass rule
    public void gasLimitEqualMinimumGasLimit() {
        Block block = getBlock(SystemProperties.CONFIG.getBlockchainConfig().getCommonConstants().getMIN_GAS_LIMIT());
        assertTrue(rule.isValid(block));
    }

    @Test // no pass rule
    public void gasLimitLessThanMinimumGasLimit() {
        Block block = getBlock(SystemProperties.CONFIG.getBlockchainConfig().getCommonConstants().getMIN_GAS_LIMIT() - 1);
        assertFalse(rule.isValid(block));
    }

    private static Block getBlock(long gasLimitValue) {
        byte[] gasLimit = new DataWord(gasLimitValue).getData();

        BlockHeader header = new BlockHeader(null, null, null, null, null, 0,
                gasLimit, 0,
                0, null, null,0);

        return new Block(header);
    }
}
