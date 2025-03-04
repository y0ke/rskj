/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
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

package org.ethereum.vm;

import co.rsk.panic.PanicProcessor;
import org.ethereum.db.ContractDetails;
import org.ethereum.vm.MessageCall.MsgType;
import org.ethereum.vm.program.Program;
import org.ethereum.vm.program.Stack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.ethereum.config.SystemProperties.CONFIG;
import static org.ethereum.crypto.HashUtil.sha3;
import static org.ethereum.util.ByteUtil.EMPTY_BYTE_ARRAY;


/**
 * The Ethereum Virtual Machine (EVM) is responsible for initialization
 * and executing a transaction on a contract.
 *
 * It is a quasi-Turing-complete machine; the quasi qualification
 * comes from the fact that the computation is intrinsically bounded
 * through a parameter, gas, which limits the total amount of computation done.
 *
 * The EVM is a simple stack-based architecture. The word size of the machine
 * (and thus size of stack item) is 256-bit. This was chosen to facilitate
 * the SHA3-256 hash scheme and  elliptic-curve computations. The memory model
 * is a simple word-addressed byte array. The stack has an unlimited size.
 * The machine also has an independent storage model; this is similar in concept
 * to the memory but rather than a byte array, it is a word-addressable word array.
 *
 * Unlike memory, which is volatile, storage is non volatile and is
 * maintained as part of the system state. All locations in both storage
 * and memory are well-defined initially as zero.
 *
 * The machine does not follow the standard von Neumann architecture.
 * Rather than storing program code in generally-accessible memory or storage,
 * it is stored separately in a virtual ROM interactable only though
 * a specialised instruction.
 *
 * The machine can have exceptional execution for several reasons,
 * including stack underflows and invalid instructions. These unambiguously
 * and validly result in immediate halting of the machine with all state changes
 * left intact. The one piece of exceptional execution that does not leave
 * state changes intact is the out-of-gas (OOG) exception.
 *
 * Here, the machine halts immediately and reports the issue to
 * the execution agent (either the transaction processor or, recursively,
 * the spawning execution environment) and which will deal with it separately.
 *
 * @author Roman Mandeleil
 * @since 01.06.2014
 */
public class VM {

    private static final Logger logger = LoggerFactory.getLogger("VM");
    private static final Logger dumpLogger = LoggerFactory.getLogger("dump");
    private static final PanicProcessor panicProcessor = new PanicProcessor();
    private static String logString = "{}    Op: [{}]  Gas: [{}] Deep: [{}]  Hint: [{}]";

    private static long MAX_GAS_LONG =Long.MAX_VALUE;


    /* Keeps track of the number of steps performed in this VM */
    private int vmCounter = 0;

    private static VMHook vmHook;
    private final static boolean vmTrace = CONFIG.vmTrace();
    private final static long dumpBlock = CONFIG.dumpBlock();
    private boolean computeGas = true; // for performance comp

    public VM() {
        isLogEnabled = logger.isInfoEnabled();
    }



    private void checkSizeArgument(long size) {
        if (size>program.MAX_MEMORY)
            // Force exception
            throw Program.Exception.notEnoughOpGas(op, Long.MAX_VALUE, program.getRemainingGas());

    }
    private long calcMemGas(long oldMemSize, long newMemSize, long copySize) {
        long gasCost = 0;

        // Avoid overflows
        checkSizeArgument(newMemSize);

        // memory gas calc
        // newMemSize has only 30 significant digits.
        // Because of quadratic cost, we'll limit the maximim memSize to 30 bits = 2^30 = 1 GB.

        // This comparison assumes (oldMemSize % 32 == 0)
        if (newMemSize > oldMemSize) { // optimization to avoid div/mul
            long memoryUsage = (newMemSize+31) / 32 * 32; // rounds up
            if (memoryUsage > oldMemSize) {
                memWords = (memoryUsage / 32); // 25 sig digits
                long memWordsOld = (oldMemSize / 32);
                long memGas;

                 // MemWords*MemWords has 50 sig digits, so this cannot overflow
                 memGas = (GasCost.MEMORY * memWords + memWords * memWords / 512)
                        - (GasCost.MEMORY * memWordsOld + memWordsOld * memWordsOld / 512);

                gasCost += memGas;
            }
        }

        // copySize is invalid if newMemSize > 2^63, but it only gets here if newMemSize is <= 2^30
        if (copySize > 0) {
            long copyGas = GasCost.COPY_GAS * ((copySize + 31) / 32);
            gasCost += copyGas;
        }

        return gasCost;
    }


    public void step(Program aprogram) {
        steps(aprogram,1);
    }

    public int getVmCounter() { // for profiling only
        return vmCounter;
    }

    public void resetVmCounter() { // for profiling only
        vmCounter =0;
    }

    // Execution variables
    Program program;
    Stack stack;
    OpCode op;
    long oldMemSize ;

    String hint ;

    long memWords; // parameters for logging
    long gasCost;
    long gasBefore; // only for tracing
    int stepBefore; // only for debugging
    boolean isLogEnabled;

    protected void checkOpcode() {
        if (op == null) {
            throw Program.Exception.invalidOpCode(program.getCurrentOp());
        }
        if (op.scriptVersion() > program.getScriptVersion())
            throw Program.Exception.invalidOpCode(program.getCurrentOp());

    }


    public static long limitedAddToMaxLong(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    protected void spendOpCodeGas() {
        if (!computeGas)
            return;
        program.spendGas(gasCost, op.name());
    }


    protected void doSTOP() {
        if (computeGas) {
            gasCost = GasCost.STOP;
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        program.setHReturn(EMPTY_BYTE_ARRAY);
        program.stop();
    }

    protected void doADD() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " + " + word2.value();

        word1.add(word2);
        program.disposeWord(word2);
        program.stackPush(word1);
        program.step();

    }

    protected void doMUL() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " * " + word2.value();

        word1.mul(word2);
        program.disposeWord(word2);
        program.stackPush(word1);
        program.step();
    }

    protected void doSUB() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " - " + word2.value();

        word1.sub(word2);
        program.disposeWord(word2);
        program.stackPush(word1);
        program.step();
    }

    protected void doDIV()  {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " / " + word2.value();

        word1.div(word2);
        program.disposeWord(word2);
        program.stackPush(word1);
        program.step();
    }

    protected void doSDIV() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.sValue() + " / " + word2.sValue();

        word1.sDiv(word2);
        program.disposeWord(word2);
        program.stackPush(word1);
        program.step();
    }

    protected void doMOD() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " % " + word2.value();

        word1.mod(word2);
        program.disposeWord(word2);
        program.stackPush(word1);
        program.step();
    }

    protected void doSMOD() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.sValue() + " #% " + word2.sValue();

        word1.sMod(word2);
        program.disposeWord(word2);
        program.stackPush(word1);
        program.step();
    }

    protected void doEXP() {
        if (computeGas) {
            DataWord exp = stack.get(stack.size() - 2);
            int bytesOccupied = exp.bytesOccupied();
            gasCost = GasCost.EXP_GAS + GasCost.EXP_BYTE_GAS * bytesOccupied;
        }
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " ** " + word2.value();

        word1.exp(word2);
        program.disposeWord(word2);
        program.stackPush(word1);
        program.step();
    }

    protected void doSIGNEXTEND()  {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        long k = Program.limitToMaxLong(word1);

        if (k<32) {
            DataWord word2 = program.stackPop();
            if (isLogEnabled)
                hint = word1 + "  " + word2.value();
            word2.signExtend((byte) k);
            program.stackPush(word2);
        }
        program.disposeWord(word1);
        program.step();
    }

    protected void doNOT() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        word1.bnot();

        if (isLogEnabled)
            hint = "" + word1.value();

        program.stackPush(word1);
        program.step();
    }

    protected void doLT() {
        spendOpCodeGas();
        // EXECUTION PHASE
        // TODO: can be improved by not using BigInteger
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " < " + word2.value();

        // TODO: We should compare the performance of BigInteger comparison with DataWord comparison:
        if (word1.compareTo(word2)==-1) {
            word1.setTrue();
        } else {
            word1.zero();
        }
        program.stackPush(word1);
        program.disposeWord(word2);
        program.step();
    }

    protected void doSLT() {
        spendOpCodeGas();
        // EXECUTION PHASE
        // TODO: can be improved by not using BigInteger
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.sValue() + " < " + word2.sValue();

        if (word1.sValue().compareTo(word2.sValue()) == -1) {
            word1.setTrue();
        } else {
            word1.zero();
        }
        program.stackPush(word1);
        program.disposeWord(word2);
        program.step();
    }

    protected void doSGT() {
        spendOpCodeGas();
        // EXECUTION PHASE
        // TODO: can be improved by not using BigInteger
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.sValue() + " > " + word2.sValue();

        if (word1.sValue().compareTo(word2.sValue()) == 1) {
            word1.setTrue();
        } else {
            word1.zero();
        }
        program.stackPush(word1);
        program.disposeWord(word2);
        program.step();
    }


    protected void doGT() {
        spendOpCodeGas();
        // EXECUTION PHASE
        // TODO: can be improved by not using BigInteger
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " > " + word2.value();

        if (word1.value().compareTo(word2.value()) == 1) {
            word1.setTrue();
        } else {
            word1.zero();
        }
        program.stackPush(word1);
        program.disposeWord(word2);
        program.step();
    }

    protected void doEQ() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " == " + word2.value();

        if (word1.equalValue(word2)) {
            word1.setTrue();
        } else {
            word1.zero();
        }
        program.stackPush(word1);
        program.disposeWord(word2);
        program.step();
    }

    protected void  doISZERO() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        if (word1.isZero()) {
            // This is an optimization: since word1 is zero, then setting only the last byte
            // to 1 is equivalent to setTrue().
            word1.getData()[31] = 1;
        } else {
            word1.zero();
        }

        if (isLogEnabled)
            hint = "" + word1.value();

        program.stackPush(word1);
        program.step();
    }

    protected void doAND(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " && " + word2.value();

        word1.and(word2);
        program.stackPush(word1);
        program.disposeWord(word2);
        program.step();
    }

    protected void doOR(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " || " + word2.value();

        word1.or(word2);
        program.stackPush(word1);
        program.disposeWord(word2);
        program.step();
    }

    protected void doXOR(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();

        if (isLogEnabled)
            hint = word1.value() + " ^ " + word2.value();

        word1.xor(word2);
        program.stackPush(word1);
        program.disposeWord(word2);
        program.step();
    }

    protected void doBYTE() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();
        final DataWord result;
        long wvalue = Program.limitToMaxLong(word1);
        if (wvalue<32) {
            byte tmp = word2.getData()[(int) wvalue];
            word2.zero();
            word2.getData()[31] = tmp;
            result = word2;
        } else {
            word2.zero();
            result = word2;
        }

        if (isLogEnabled)
            hint = "" + result.value();

        program.stackPush(result);
        program.disposeWord(word1);
        program.step();
    }

    protected void doADDMOD() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();
        DataWord word3 = program.stackPop();
        word1.addmod(word2, word3);
        program.stackPush(word1);
        program.disposeWord(word2);
        program.disposeWord(word3);
        program.step();
    }

    protected void doMULMOD() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord word1 = program.stackPop();
        DataWord word2 = program.stackPop();
        DataWord word3 = program.stackPop();
        word1.mulmod(word2, word3);
        program.stackPush(word1);
        program.disposeWord(word2);
        program.disposeWord(word3);
        program.step();
    }

    protected void doSHA3() {
        DataWord size;
        long sizeLong;
        long newMemSize ;
        if (computeGas) {
            gasCost = GasCost.SHA3;
            size = stack.get(stack.size() - 2);
            sizeLong = Program.limitToMaxLong(size);
            checkSizeArgument(sizeLong);
            newMemSize = memNeeded(stack.peek(), sizeLong);
            long chunkUsed = (sizeLong + 31) / 32;
            gasCost += chunkUsed * GasCost.SHA3_WORD;
            gasCost += calcMemGas(oldMemSize, newMemSize, 0);

            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord memOffsetData = program.stackPop();
        DataWord lengthData = program.stackPop();
        byte[] buffer = program.memoryChunk(memOffsetData.intValue(), lengthData.intValue());

        byte[] encoded = sha3(buffer);
        DataWord word = program.newDataWord(encoded);

        if (isLogEnabled)
            hint = word.toString();

        program.stackPush(word);
        program.disposeWord(memOffsetData);
        program.disposeWord(lengthData);
        program.step();
    }

    protected void doADDRESS() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord address = program.getOwnerAddress();
        DataWord dwAddress = program.newDataWord(address);

        if (isLogEnabled)
            hint = "address: " + Hex.toHexString(address.getLast20Bytes());

        program.stackPush(dwAddress);
        program.step();
    }

    protected void doBALANCE() {
        if (computeGas) {
            gasCost = GasCost.BALANCE;
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord address = program.stackPop();
        DataWord balance = program.getBalance(address); // TODO: should not allocate

        if (isLogEnabled)
            hint = "address: "
                    + Hex.toHexString(address.getLast20Bytes())
                    + " balance: " + balance.toString();

        program.stackPush(balance);
        program.disposeWord(address);
        program.step();
    }

    protected void doORIGIN(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord originAddress = program.getOriginAddress();

        if (isLogEnabled)
            hint = "address: " + Hex.toHexString(originAddress.getLast20Bytes());

        program.stackPush(originAddress);
        program.step();
    }

    protected void doCALLER()  {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord callerAddress = program.getCallerAddress();

        if (isLogEnabled)
            hint = "address: " + Hex.toHexString(callerAddress.getLast20Bytes());

        program.stackPush(callerAddress);
        program.step();
    }

    protected void doCALLVALUE() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord callValue = program.getCallValue();

        if (isLogEnabled)
            hint = "value: " + callValue;

        program.stackPush(callValue);
        program.step();
    }

    protected void  doCALLDATALOAD() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord dataOffs = program.stackPop();
        DataWord value = program.getDataValue(dataOffs);

        if (isLogEnabled)
            hint = "data: " + value;

        program.stackPush(value);
        program.disposeWord(dataOffs);
        program.step();
    }

    protected void doCALLDATASIZE() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord dataSize = program.getDataSize();

        if (isLogEnabled)
            hint = "size: " + dataSize.value();

        program.stackPush(dataSize);
        program.step();
    }

    protected void doCALLDATACOPY() {
        DataWord size;
        long sizeLong;
        long newMemSize ;
        long copySize;

        if (computeGas) {
            size = stack.get(stack.size() - 3);
            copySize = Program.limitToMaxLong(size);
            checkSizeArgument(copySize);
            newMemSize = memNeeded(stack.peek(), copySize);
            gasCost += calcMemGas(oldMemSize, newMemSize, copySize);
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord memOffsetData = program.stackPop();
        DataWord dataOffsetData = program.stackPop();
        DataWord lengthData = program.stackPop();

        byte[] msgData = program.getDataCopy(dataOffsetData, lengthData);

        if (isLogEnabled)
            hint = "data: " + Hex.toHexString(msgData);

        program.memorySave(memOffsetData.intValue(), msgData);
        program.disposeWord(memOffsetData);
        program.disposeWord(dataOffsetData);
        program.disposeWord(lengthData);
        program.step();
    }

    protected void doCODESIZE() {
        if (computeGas) {
            if (op == OpCode.EXTCODESIZE)
                gasCost = GasCost.EXT_CODE_SIZE;
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        int length;
        if (op == OpCode.CODESIZE)
            //TODO(mmarquez): we need to add support to precompiled contracts
            length = program.getCode().length; // during initialization it will return the initialization code size
        else {
            DataWord address = program.stackPop();
            length = program.getCodeAt(address).length;
            program.disposeWord(address);
        }
        DataWord codeLength = new DataWord(length);

        if (isLogEnabled)
            hint = "size: " + length;

        program.stackPush(codeLength);

        program.step();
    }

    protected void doCODECOPY() {
        DataWord size;
        long sizeLong;
        long newMemSize ;
        long copySize;
        if (computeGas) {

            if (op == OpCode.EXTCODECOPY) {
                gasCost = GasCost.EXT_CODE_COPY;
                size = stack.get(stack.size() - 4);
                copySize = Program.limitToMaxLong(size);
                checkSizeArgument(copySize);
                newMemSize = memNeeded(stack.get(stack.size() - 2), copySize);
                gasCost += calcMemGas(oldMemSize, newMemSize, copySize);
            } else {
                size = stack.get(stack.size() - 3);
                copySize = Program.limitToMaxLong(size);
                checkSizeArgument(copySize);
                newMemSize = memNeeded(stack.peek(), copySize);
                gasCost += calcMemGas(oldMemSize, newMemSize, copySize);
            }
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        // case OpCodes.opCODECOPY:
        // case OpCodes.opEXTCODECOPY

        byte[] fullCode = EMPTY_BYTE_ARRAY;
        if (op == OpCode.CODECOPY)
            fullCode = program.getCode();

        if (op == OpCode.EXTCODECOPY) {
            DataWord address = program.stackPop();
            fullCode = program.getCodeAt(address);
            program.disposeWord(address);
        }

        DataWord memOffsetDW = program.stackPop();
        DataWord codeOffsetDW = program.stackPop();
        DataWord lengthDataDW = program.stackPop();

        // Here size/offsets fit in ints are assumed: this is consistent with
        // maximum memory size, which is 1 GB (program.MAX_MEMORY)
        int memOffset = memOffsetDW .intValueSafe();
        int codeOffset = codeOffsetDW.intValueSafe(); // where to start reading
        int lengthData = lengthDataDW.intValueSafe(); // amount of bytes to copy

        int sizeToBeCopied;
        if ((long) codeOffset + lengthData > fullCode.length) {
            // if user wants to read more info from code what actual code has then..
            // if all code that users wants lies after code has ended..
            if (codeOffset >=fullCode.length)
                sizeToBeCopied=0; // do not copy anything
            else
                sizeToBeCopied = fullCode.length - codeOffset; // copy only the remaining

        } else
           // Code is longer, so limit by user length value
            sizeToBeCopied =lengthData;

        // The part not copied must be filled with zeros, so here we allocate
        // enough space to contain filling also.
        byte[] codeCopy = new byte[lengthData];

        if (codeOffset < fullCode.length)
            System.arraycopy(fullCode, codeOffset, codeCopy, 0, sizeToBeCopied);

        if (isLogEnabled)
            hint = "code: " + Hex.toHexString(codeCopy);

        // TODO: an optimization to avoid double-copying would be to override programSave
        // to receive a byte[] buffer and a length, and to create another method memoryZero(offset,length)
        // to fill the gap.
        program.memorySave(memOffset, codeCopy);
        program.disposeWord(memOffsetDW);
        program.disposeWord(codeOffsetDW);
        program.disposeWord(lengthDataDW);

        program.step();
    }

    protected void doGASPRICE(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord gasPrice = program.getGasPrice();

        if (isLogEnabled)
            hint = "price: " + gasPrice.toString();

        program.stackPush(gasPrice);
        program.step();
    }

    protected void doBLOCKHASH() {
        spendOpCodeGas();
        // EXECUTION PHASE

        DataWord blockIndexDW = program.stackPop();

        DataWord blockHash = program.getBlockHash(blockIndexDW);

        if (isLogEnabled)
            hint = "blockHash: " + blockHash;

        program.stackPush(blockHash);
        program.step();
    }

    protected void doCOINBASE() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord coinbase = program.getCoinbase();

        if (isLogEnabled)
            hint = "coinbase: " + Hex.toHexString(coinbase.getLast20Bytes());

        program.stackPush(coinbase);
        program.step();
    }

    protected void doTIMESTAMP() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord timestamp = program.getTimestamp();

        if (isLogEnabled)
            hint = "timestamp: " + timestamp.value();

        program.stackPush(timestamp);
        program.step();
    }

    protected void doNUMBER(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord number = program.getNumber();

        if (isLogEnabled)
            hint = "number: " + number.value();

        program.stackPush(number);
        program.step();
    }

    protected void doDIFFICULTY() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord difficulty = program.getDifficulty();

        if (isLogEnabled)
            hint = "difficulty: " + difficulty;

        program.stackPush(difficulty);
        program.step();
    }

    protected void doGASLIMIT() {
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord gaslimit = program.getGasLimit();

        if (isLogEnabled)
            hint = "gaslimit: " + gaslimit;

        program.stackPush(gaslimit);
        program.step();
    }

    protected void doPOP(){
        spendOpCodeGas();
        // EXECUTION PHASE
        program.disposeWord(program.stackPop());
        program.step();
    }

    protected void doDUP() {
        spendOpCodeGas();
        // EXECUTION PHASE
        int n = op.val() - OpCode.DUP1.val() + 1;
        DataWord word_1 = stack.get(stack.size() - n);
        program.stackPush(program.newDataWord(word_1));
        program.step();
    }

    protected void doSWAP(){
        spendOpCodeGas();
        // EXECUTION PHASE
        int n = op.val() - OpCode.SWAP1.val() + 2;
        stack.swap(stack.size() - 1, stack.size() - n);
        program.step();
    }

    protected void doLOG(){
        DataWord size;
        long sizeLong;
        long newMemSize ;
        long copySize;
        int nTopics = op.val() - OpCode.LOG0.val();

        if (computeGas) {
            size = stack.get(stack.size() - 2);
            sizeLong = Program.limitToMaxLong(size);
            checkSizeArgument(sizeLong);
            newMemSize = memNeeded(stack.peek(), sizeLong);

            long dataCost = Program.multiplyLimitToMaxLong(sizeLong, GasCost.LOG_DATA_GAS);

            if (dataCost > program.MAX_GAS)
                throw Program.Exception.notEnoughOpGas(op, dataCost, program.getRemainingGas());

            gasCost = GasCost.LOG_GAS +
                    GasCost.LOG_TOPIC_GAS * nTopics +
                    dataCost;

            gasCost += calcMemGas(oldMemSize, newMemSize, 0);

            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord address = program.getOwnerAddress();

        DataWord memStart = stack.pop();
        DataWord memOffset = stack.pop();

        List<DataWord> topics = new ArrayList<>();
        for (int i = 0; i < nTopics; ++i) {
            DataWord topic = stack.pop();
            topics.add(topic);
        }

        // Int32 address values guaranteed by previous MAX_MEMORY checks
        byte[] data = program.memoryChunk(memStart.intValue(), memOffset.intValue());

        LogInfo logInfo =
                new LogInfo(address.getLast20Bytes(), topics, data);

        if (isLogEnabled)
            hint = logInfo.toString();

        program.getResult().addLogInfo(logInfo);
        // Log topics taken from the stack are lost and never returned to the DataWord pool
        program.disposeWord(memStart);
        program.disposeWord(memOffset);
        program.step();
    }

    protected void doMLOAD(){
        DataWord size;
        long sizeLong;
        long newMemSize ;
        long copySize;

        if (computeGas) {
            newMemSize = memNeeded(stack.peek(), 32);
            gasCost += calcMemGas(oldMemSize, newMemSize, 0);
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord addr = program.stackPop();
        DataWord data = program.memoryLoad(addr);

        if (isLogEnabled)
            hint = "data: " + data;

        program.stackPush(data);
        program.disposeWord(addr);
        program.step();
    }

    protected void doMSTORE() {
        DataWord size;
        long sizeLong;
        long newMemSize ;

        if (computeGas) {
            newMemSize = memNeeded(stack.peek(), 32);
            gasCost += calcMemGas(oldMemSize, newMemSize, 0);
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord addr = program.stackPop();
        DataWord value = program.stackPop();

        if (isLogEnabled)
            hint = "addr: " + addr + " value: " + value;

        program.memorySave(addr, value);
        program.disposeWord(addr);
        program.disposeWord(value);
        program.step();
    }

    protected void doMSTORE8(){
        DataWord size;
        long sizeLong;
        long newMemSize ;

        if (computeGas) {
            newMemSize = memNeeded(stack.peek(), 1);
            gasCost += calcMemGas(oldMemSize, newMemSize, 0);

            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord addr = program.stackPop();
        DataWord value = program.stackPop();
        byte[] byteVal = {value.getData()[31]};
        //TODO: non-standard single byte memory storage, this should be documented
        program.memorySave(addr.intValue(), byteVal);
        program.disposeWord(addr);
        program.disposeWord(value);
        program.step();
    }


    protected void doSLOAD() {
        if (computeGas) {
            gasCost = GasCost.SLOAD;
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord key = program.stackPop();
        DataWord val = program.storageLoad(key);

        if (isLogEnabled)
            hint = "key: " + key + " value: " + val;

        if (val == null)
            val = key.zero();

        program.stackPush(val);
        // key could be returned to the pool, but storageLoad semantics should be checked
        // to make sure storageLoad always gets a copy, not a reference.
        program.step();
    }

    protected void doSSTORE() {
        if (computeGas) {
            DataWord newValue = stack.get(stack.size() - 2);
            DataWord oldValue = program.storageLoad(stack.peek());

            // From null to non-zero
            if (oldValue == null && !newValue.isZero())
                gasCost = GasCost.SET_SSTORE;

                // from non-zero to zero
            else if (oldValue != null && newValue.isZero()) {
                // todo: GASREFUND counter policyn

                // refund step cost policy.
                program.futureRefundGas(GasCost.REFUND_SSTORE);
                gasCost = GasCost.CLEAR_SSTORE;
            } else
                // from zero to zero, or from non-zero to non-zero
                gasCost = GasCost.RESET_SSTORE;

            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord addr = program.stackPop();
        DataWord value = program.stackPop();

        if (isLogEnabled)
            hint = "[" + program.getOwnerAddress().toPrefixString() + "] key: " + addr + " value: " + value;

        program.storageSave(addr, value);
        program.disposeWord(addr);
        program.disposeWord(value);
        program.step();
    }

    protected void doJUMP(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord pos = program.stackPop();
        int nextPC = program.verifyJumpDest(pos);

        if (isLogEnabled)
            hint = "~> " + nextPC;

        program.setPC(nextPC);
        program.disposeWord(pos);

    }

    protected void doJUMPI(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord pos = program.stackPop();
        DataWord cond = program.stackPop();

        if (!cond.isZero()) {
            int nextPC = program.verifyJumpDest(pos);

            if (isLogEnabled)
                hint = "~> " + nextPC;

            program.setPC(nextPC);

        } else {
            program.step();
        }
        program.disposeWord(pos);
        program.disposeWord(cond);
    }

    protected void doPC(){
        spendOpCodeGas();
        // EXECUTION PHASE
        int pc = program.getPC();
        DataWord pcWord = program.newDataWord(pc);

        if (isLogEnabled)
            hint = pcWord.toString();

        program.stackPush(pcWord);
        program.step();
    }

    protected void doMSIZE(){
        spendOpCodeGas();
        // EXECUTION PHASE
        int memSize = program.getMemSize();
        DataWord wordMemSize = program.newDataWord(memSize);

        if (isLogEnabled)
            hint = "" + memSize;

        program.stackPush(wordMemSize);
        program.step();
    }

    protected void doGAS(){
        spendOpCodeGas();
        // EXECUTION PHASE
        DataWord gas = program.newDataWord(program.getRemainingGas());

        if (isLogEnabled)
            hint = "" + gas;

        program.stackPush(gas);
        program.step();
    }

    protected void doPUSH(){
        spendOpCodeGas();
        // EXECUTION PHASE
        program.step();
        int nPush = op.val() - OpCode.PUSH1.val() + 1;

        DataWord data = program.sweepGetDataWord(nPush);

        if (isLogEnabled)
            hint = "" + Hex.toHexString(data.getData());

        program.stackPush(data);
    }

    protected void doJUMPDEST()
    {
        spendOpCodeGas();
        // EXECUTION PHASE
        program.step();
    }

    protected void doCREATE(){
        DataWord size;
        long sizeLong;
        long newMemSize ;
        long copySize;

        if (computeGas) {
            gasCost = GasCost.CREATE;
            size = stack.get(stack.size() - 3);
            sizeLong = Program.limitToMaxLong(size);
            checkSizeArgument(sizeLong);
            newMemSize = memNeeded(stack.get(stack.size() - 2), sizeLong);
            gasCost += calcMemGas(oldMemSize, newMemSize, 0);

            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord value = program.stackPop();
        DataWord inOffset = program.stackPop();
        DataWord inSize = program.stackPop();

        if (isLogEnabled)
            logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                    String.format("%-12s", op.name()),
                    program.getRemainingGas(),
                    program.getCallDeep(), hint);

        program.createContract(value, inOffset, inSize);
        program.disposeWord(value);
        program.disposeWord(inOffset);
        program.disposeWord(inSize);
        program.step();
    }

    protected void doCALL(){
        DataWord size;
        long sizeLong;
        long newMemSize ;
        long copySize;

        DataWord value;

        if (!op.equals(OpCode.DELEGATECALL))
            value = stack.get(stack.size() - 3);
        else
            value = DataWord.ZERO;

        DataWord callGasWord = stack.get(stack.size() - 1);
        long callGasWordLong = Program.limitToMaxLong(callGasWord);

        if (computeGas) {
            gasCost = GasCost.CALL;

            DataWord callAddressWord = stack.get(stack.size() - 2);
            //check to see if account does not exist and is not a precompiled contract
            if (op != OpCode.CALLCODE && !program.getStorage().isExist(callAddressWord.getLast20Bytes()))
                gasCost += GasCost.NEW_ACCT_CALL;

            if (!stack.get(stack.size() - 3).isZero())
                gasCost += GasCost.VT_CALL;


            int opOff = op == OpCode.DELEGATECALL ? 3 : 4;
            DataWord inOfs = stack.get(stack.size() - opOff);
            DataWord inSize = stack.get(stack.size() - opOff - 1);
            DataWord outOfs = stack.get(stack.size() - opOff - 2);
            DataWord outSize = stack.get(stack.size() - opOff - 3);

            long inSizeLong = Program.limitToMaxLong(inSize);
            long outSizeLong = Program.limitToMaxLong(outSize);

            long in = memNeeded(inOfs, inSizeLong); // in offset+size
            long out = memNeeded(outOfs, outSizeLong); // out offset+size
            newMemSize = Long.max(in, out);
            gasCost += calcMemGas(oldMemSize, newMemSize, 0);
        }

        long requiredGas = gasCost;

        if (!value.isZero()) {
            requiredGas +=GasCost.STIPEND_CALL; // This is decremented later in message call
         }

        // The following is an important gas check. DO NOT REMOVE.
        // If callGasWordLong is higher than available gas, then move all gas to callee.
        // Before, it rose an exception

        long remGas = program.getRemainingGas()-requiredGas ;

        if (remGas < 0)
            throw Program.Exception.gasOverflow(BigInteger.valueOf(program.getRemainingGas()), BigInteger.valueOf(requiredGas));

        if (callGasWordLong>remGas) {
            // This would rise an exception pre EIP150
            callGasWordLong = remGas;
        }

        if (computeGas) {
            gasCost += callGasWordLong;

            spendOpCodeGas();
        }

        // EXECUTION PHASE
        program.stackPop(); // gas
        DataWord codeAddress = program.stackPop();

        if (!op.equals(OpCode.DELEGATECALL))
            program.stackPop(); // value

        DataWord inDataOffs = program.stackPop();
        DataWord inDataSize = program.stackPop();

        DataWord outDataOffs = program.stackPop();
        DataWord outDataSize = program.stackPop();

        if (isLogEnabled) {
            hint = "addr: " + Hex.toHexString(codeAddress.getLast20Bytes())
                    + " gas: " + callGasWordLong
                    + " inOff: " + inDataOffs.shortHex()
                    + " inSize: " + inDataSize.shortHex();
            logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                    String.format("%-12s", op.name()),
                    program.getRemainingGas(),
                    program.getCallDeep(), hint);
        }

        program.memoryExpand(outDataOffs, outDataSize);

        MessageCall msg = new MessageCall(
                MsgType.fromOpcode(op),
                new DataWord(callGasWordLong), codeAddress, value, inDataOffs, inDataSize,
                outDataOffs, outDataSize);

        PrecompiledContracts.PrecompiledContract contract =
                PrecompiledContracts.getContractForAddress(codeAddress);

        if (contract != null) {
            program.callToPrecompiledAddress(msg, contract);
        } else {
            program.callToAddress(msg);
        }
        program.disposeWord(inDataOffs);
        program.disposeWord(inDataSize);
        program.disposeWord(outDataOffs);
        program.disposeWord(outDataSize);
        program.disposeWord(codeAddress);
        program.disposeWord(callGasWord);
        if (!op.equals(OpCode.DELEGATECALL))
            program.disposeWord(value);
        program.step();
    }

    protected void doRETURN(){
        DataWord size;
        long sizeLong;
        long newMemSize ;
        long copySize;

        size = stack.get(stack.size() - 2);

        if (computeGas) {
            gasCost = GasCost.RETURN;
            sizeLong = Program.limitToMaxLong(size);
            checkSizeArgument(sizeLong);
            newMemSize = memNeeded(stack.peek(), sizeLong);
            gasCost += calcMemGas(oldMemSize, newMemSize, 0);

            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord offset = program.stackPop();
        program.stackPop(); // pops size

        byte[] hReturn = program.memoryChunk(offset.intValue(), size.intValue());
        program.setHReturn(hReturn);

        if (isLogEnabled)
            hint = "data: " + Hex.toHexString(hReturn)
                    + " offset: " + offset.value()
                    + " size: " + size.value();

        program.step();
        program.disposeWord(offset);
        program.disposeWord(size);
        program.stop();
    }

    protected void doSUICIDE(){
        if (computeGas) {
            gasCost = GasCost.SUICIDE;
            DataWord suicideAddressWord = stack.get(stack.size() - 1);
            if (!program.getStorage().isExist(suicideAddressWord.getLast20Bytes()))
                gasCost += GasCost.NEW_ACCT_SUICIDE;
            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord address = program.stackPop();
        program.suicide(address);

        if (isLogEnabled)
            hint = "address: " + Hex.toHexString(program.getOwnerAddress().getLast20Bytes());

        program.disposeWord(address);
        program.stop();
    }

    protected void doCODEREPLACE() {

        DataWord size;
        long newCodeSizeLong;
        long newMemSize ;
        if (computeGas) {
            gasCost = GasCost.CODEREPLACE;
            size = stack.get(stack.size() - 2);
            newCodeSizeLong = Program.limitToMaxLong(size);
            checkSizeArgument(newCodeSizeLong); // max 30 bits
            newMemSize = memNeeded(stack.peek(), newCodeSizeLong); // max 30 bits
            gasCost += calcMemGas(oldMemSize, newMemSize, 0); // max 32 bits
            long oldCodeSize = program.getCode().length;

            // If the contract is been created (initialization code is been executed)
            // then the meaning of codereplace is less clear. It's better to disallow it.
            long storedLength = program.getCodeAt(program.getOwnerAddressLast20Bytes()).length;
            if (storedLength == 0) { // rise OOG, but a specific exception would be better
                throw Program.Exception.notEnoughOpGas(op, Long.MAX_VALUE, program.getRemainingGas());
            }

            // every byte replaced pays REPLACE_DATA
            // every byte added pays CREATE_DATA
            if (newCodeSizeLong <= oldCodeSize)
                gasCost += GasCost.REPLACE_DATA * newCodeSizeLong; // max 38 bits
            else {
                gasCost += GasCost.REPLACE_DATA * oldCodeSize;
                gasCost += GasCost.CREATE_DATA * (newCodeSizeLong-oldCodeSize);
            }

            spendOpCodeGas();
        }
        // EXECUTION PHASE
        DataWord memOffsetData = program.stackPop();
        DataWord lengthData = program.stackPop();
        byte[] buffer = program.memoryChunk(memOffsetData.intValue(), lengthData.intValue());
        int resultInt = program.replaceCode(buffer);

        DataWord result = program.newDataWord(resultInt);

        if (isLogEnabled)
            hint = result.toString();

        program.stackPush(result);
        program.disposeWord(memOffsetData);
        program.disposeWord(lengthData);
        program.step();
    }

    protected void executeOpcode() {
        // Execute operation
        switch (op.val()) {
            /**
             * Stop and Arithmetic Operations
             */
            case OpCodes.opSTOP: doSTOP();
            break;
            case OpCodes.opADD: doADD();
            break;
            case OpCodes.opMUL: doMUL();
            break;
            case OpCodes.opSUB: doSUB();
            break;
            case OpCodes.opDIV: doDIV();
            break;
            case OpCodes.opSDIV: doSDIV();
            break;
            case OpCodes.opMOD: doMOD();
            break;
            case OpCodes.opSMOD: doSMOD();
            break;
            case OpCodes.opEXP: doEXP();
            break;
            case OpCodes.opSIGNEXTEND: doSIGNEXTEND();
            break;
            case OpCodes.opNOT: doNOT();
            break;
            case OpCodes.opLT: doLT();
            break;
            case OpCodes.opSLT: doSLT();
            break;
            case OpCodes.opSGT: doSGT();
            break;
            case OpCodes.opGT: doGT();
            break;
            case OpCodes.opEQ: doEQ();
            break;
            case OpCodes.opISZERO: doISZERO();
            break;
            /**
             * Bitwise Logic Operations
             */
            case OpCodes.opAND: doAND();
            break;
            case OpCodes.opOR: doOR();
            break;
            case OpCodes.opXOR: doXOR();
            break;
            case OpCodes.opBYTE: doBYTE();
            break;
            case OpCodes.opADDMOD: doADDMOD();
            break;
            case OpCodes.opMULMOD: doMULMOD();
            break;
            /**
             * SHA3
             */
            case OpCodes.opSHA3: doSHA3();
            break;

            /**
             * Environmental Information
             */
            case OpCodes.opADDRESS: doADDRESS();
            break;
            case OpCodes.opBALANCE: doBALANCE();
            break;
            case OpCodes.opORIGIN: doORIGIN();
            break;
            case OpCodes.opCALLER: doCALLER();
            break;
            case OpCodes.opCALLVALUE: doCALLVALUE();
            break;
            case OpCodes.opCALLDATALOAD: doCALLDATALOAD();
            break;
            case OpCodes.opCALLDATASIZE: doCALLDATASIZE();
            break;
            case OpCodes.opCALLDATACOPY: doCALLDATACOPY();
            break;
            case OpCodes.opCODESIZE:
            case OpCodes.opEXTCODESIZE: doCODESIZE();
                break;
            case OpCodes.opCODECOPY:
            case OpCodes.opEXTCODECOPY: doCODECOPY();
            break;
            case OpCodes.opGASPRICE: doGASPRICE();
            break;

            /**
             * Block Information
             */
            case OpCodes.opBLOCKHASH: doBLOCKHASH();
            break;
            case OpCodes.opCOINBASE: doCOINBASE();
            break;
            case OpCodes.opTIMESTAMP: doTIMESTAMP();
            break;
            case OpCodes.opNUMBER: doNUMBER();
            break;
            case OpCodes.opDIFFICULTY: doDIFFICULTY();
            break;
            case OpCodes.opGASLIMIT: doGASLIMIT();
            break;
            case OpCodes.opPOP: doPOP();
            break;
            case OpCodes.opDUP1:
            case OpCodes.opDUP2:
            case OpCodes.opDUP3:
            case OpCodes.opDUP4:
            case OpCodes.opDUP5:
            case OpCodes.opDUP6:
            case OpCodes.opDUP7:
            case OpCodes.opDUP8:
            case OpCodes.opDUP9:
            case OpCodes.opDUP10:
            case OpCodes.opDUP11:
            case OpCodes.opDUP12:
            case OpCodes.opDUP13:
            case OpCodes.opDUP14:
            case OpCodes.opDUP15:
            case OpCodes.opDUP16: doDUP();
            break;
            case OpCodes.opSWAP1:
            case OpCodes.opSWAP2:
            case OpCodes.opSWAP3:
            case OpCodes.opSWAP4:
            case OpCodes.opSWAP5:
            case OpCodes.opSWAP6:
            case OpCodes.opSWAP7:
            case OpCodes.opSWAP8:
            case OpCodes.opSWAP9:
            case OpCodes.opSWAP10:
            case OpCodes.opSWAP11:
            case OpCodes.opSWAP12:
            case OpCodes.opSWAP13:
            case OpCodes.opSWAP14:
            case OpCodes.opSWAP15:
            case OpCodes.opSWAP16: doSWAP();
            break;
            case OpCodes.opLOG0:
            case OpCodes.opLOG1:
            case OpCodes.opLOG2:
            case OpCodes.opLOG3:
            case OpCodes.opLOG4: doLOG();
            break;
            case OpCodes.opMLOAD: doMLOAD();
            break;
            case OpCodes.opMSTORE: doMSTORE();
            break;
            case OpCodes.opMSTORE8: doMSTORE8();
            break;
            case OpCodes.opSLOAD: doSLOAD();
            break;
            case OpCodes.opSSTORE: doSSTORE();
            break;
            case OpCodes.opJUMP: doJUMP();
            break;
            case OpCodes.opJUMPI: doJUMPI();
                break;
            case OpCodes.opPC: doPC();
            break;
            case OpCodes.opMSIZE: doMSIZE();
            break;
            case OpCodes.opGAS: doGAS();
            break;

            case OpCodes.opPUSH1:
            case OpCodes.opPUSH2:
            case OpCodes.opPUSH3:
            case OpCodes.opPUSH4:
            case OpCodes.opPUSH5:
            case OpCodes.opPUSH6:
            case OpCodes.opPUSH7:
            case OpCodes.opPUSH8:
            case OpCodes.opPUSH9:
            case OpCodes.opPUSH10:
            case OpCodes.opPUSH11:
            case OpCodes.opPUSH12:
            case OpCodes.opPUSH13:
            case OpCodes.opPUSH14:
            case OpCodes.opPUSH15:
            case OpCodes.opPUSH16:
            case OpCodes.opPUSH17:
            case OpCodes.opPUSH18:
            case OpCodes.opPUSH19:
            case OpCodes.opPUSH20:
            case OpCodes.opPUSH21:
            case OpCodes.opPUSH22:
            case OpCodes.opPUSH23:
            case OpCodes.opPUSH24:
            case OpCodes.opPUSH25:
            case OpCodes.opPUSH26:
            case OpCodes.opPUSH27:
            case OpCodes.opPUSH28:
            case OpCodes.opPUSH29:
            case OpCodes.opPUSH30:
            case OpCodes.opPUSH31:
            case OpCodes.opPUSH32: doPUSH();
            break;
            case OpCodes.opJUMPDEST: doJUMPDEST();
            break;
            case OpCodes.opCREATE: doCREATE();
            break;
            case OpCodes.opCALL:
            case OpCodes.opCALLCODE:
            case OpCodes.opDELEGATECALL: doCALL();
            break;
            case OpCodes.opRETURN: doRETURN();
            break;
            case OpCodes.opSUICIDE: doSUICIDE();
            break;
            case OpCodes.opCODEREPLACE: doCODEREPLACE();
            break;
            case OpCodes.opHEADER:
                //fallthrough to default case until implementation's ready
            default:
                // It should never execute this line.
                // We rise an exception to prevent DoS attacks that halt the node, in case of a bug.
                throw Program.Exception.invalidOpCode(program.getCurrentOp());
        }
    }

    protected void logOpCode() {
        if (isLogEnabled && !op.equals(OpCode.CALL)
                && !op.equals(OpCode.CALLCODE)
                && !op.equals(OpCode.CREATE))
            logger.info(logString, String.format("%5s", "[" + program.getPC() + "]"),
                    String.format("%-12s",
                            op.name()), program.getRemainingGas(),
                    program.getCallDeep(), hint);
    }

    public void steps(Program aprogram, long steps) {
        program = aprogram;
        stack = program.getStack();

        try {

            for(long s=0;s<steps;s++) {
                if (program.isStopped()) break;

                if (vmTrace)
                    program.saveOpTrace();

                op = OpCode.code(program.getCurrentOp());

                checkOpcode();
                program.setLastOp(op.val());
                program.verifyStackSize(op.require());
                program.verifyStackOverflow(op.require(), op.ret()); //Check not exceeding stack limits

                //TODO: There is no need to compute oldMemSize for arithmetic opcodes.
                //But this three initializations and memory computations could be done
                //in opcodes requiring memory access only.
                oldMemSize = program.getMemSize();


                if (isLogEnabled)
                    hint = "";

                gasCost = op.getTier().asInt();

                if (dumpBlock>=0) {
                    gasBefore = program.getRemainingGas();
                    stepBefore = program.getPC();
                    memWords = 0; // parameters for logging
                }

                // Log debugging line for VM
                if ((dumpBlock>=0) &&  (program.getNumber().intValue() == dumpBlock))
                    this.dumpLine(op, gasBefore, gasCost , memWords, program);

                if (vmHook != null) {
                    vmHook.step(program, op);
                }
                executeOpcode();
                program.setPreviouslyExecutedOp(op.val());
                logOpCode();
                vmCounter++;
            } // for
        } catch (RuntimeException e) {
                logger.warn("VM halted: [{}]", e);
                panicProcessor.panic("vm", String.format("VM halted: [%s]", e.getMessage()));
                program.spendAllGas();
                program.resetFutureRefund();
                program.stop();
                throw e;
        } finally {
            if (isLogEnabled) // this must be prevented because it's slow!
                program.fullTrace();
        }
    }

    public void initDebugData() {
        gasBefore = 0;
        stepBefore = 0;
        memWords = 0;
    }

    public void play(Program program) {
        try {
            if (vmHook != null) {
                vmHook.startPlay(program);
            }

            initDebugData();
            this.steps(program,Long.MAX_VALUE);

            if (vmHook != null) {
                vmHook.stopPlay(program);
            }

        } catch (RuntimeException e) {
            program.setRuntimeFailure(e);
        } catch (StackOverflowError soe){
            logger.error("\n !!! StackOverflowError: update your java run command with -Xss32M !!!\n");
            System.exit(-1);
        }
    }

    public static void setVmHook(VMHook vmHook) {
        VM.vmHook = vmHook;
    }

    /**
     * Utility to calculate new total memory size needed for an operation.
     * <br/> Basically just offset + size, unless size is 0, in which case the result is also 0.
     *
     * @param offset starting position of the memory
     * @param size number of bytes needed
     * @return offset + size, unless size is 0. In that case memNeeded is also 0.
     */

    private static long memNeeded(DataWord offset, long size) {
        return (size==0)? 0 : limitedAddToMaxLong(Program.limitToMaxLong(offset.value()),size);
    }

    /*
     * Dumping the VM state at the current operation in various styles
     *  - standard  Not Yet Implemented
     *  - standard+ (owner address, program counter, operation, gas left)
     *  - pretty (stack, memory, storage, level, contract,
     *              vmCounter, internalSteps, operation
                    gasBefore, gasCost, memWords)
     */
    private void dumpLine(OpCode op, long gasBefore, long gasCost, long memWords, Program program) {
        if (CONFIG.dumpStyle().equals("standard+")) {
            switch (op) {
                case STOP:
                case RETURN:
                case SUICIDE:

                    ContractDetails details = program.getStorage()
                            .getContractDetails(program.getOwnerAddress().getLast20Bytes());
                    List<DataWord> storageKeys = new ArrayList<>(details.getStorage().keySet());
                    Collections.sort(storageKeys);

                    for (DataWord key : storageKeys) {
                        dumpLogger.trace("{} {}",
                                Hex.toHexString(key.getNoLeadZeroesData()),
                                Hex.toHexString(details.getStorage().get(key).getNoLeadZeroesData()));
                    }
                default:
                    break;
            }
            String addressString = Hex.toHexString(program.getOwnerAddress().getLast20Bytes());
            String pcString = Hex.toHexString(new DataWord(program.getPC()).getNoLeadZeroesData());
            String opString = Hex.toHexString(new byte[]{op.val()});
            String gasString = Long.toHexString(program.getRemainingGas());

            dumpLogger.trace("{} {} {} {}", addressString, pcString, opString, gasString);
        } else if (CONFIG.dumpStyle().equals("pretty")) {
            dumpLogger.trace("-------------------------------------------------------------------------");
            dumpLogger.trace("    STACK");
            for (DataWord item : program.getStack()) {
                dumpLogger.trace("{}", item);
            }
            dumpLogger.trace("    MEMORY");
            String memoryString = program.memoryToString();
            if (!"".equals(memoryString))
                dumpLogger.trace("{}", memoryString);

            dumpLogger.trace("    STORAGE");
            ContractDetails details = program.getStorage()
                    .getContractDetails(program.getOwnerAddress().getLast20Bytes());
            List<DataWord> storageKeys = new ArrayList<>(details.getStorage().keySet());
            Collections.sort(storageKeys);

            for (DataWord key : storageKeys) {
                dumpLogger.trace("{}: {}",
                        key.shortHex(),
                        details.getStorage().get(key).shortHex());
            }

            int level = program.getCallDeep();
            String contract = Hex.toHexString(program.getOwnerAddress().getLast20Bytes());
            String internalSteps = String.format("%4s", Integer.toHexString(program.getPC())).replace(' ', '0').toUpperCase();
            dumpLogger.trace("{} | {} | #{} | {} : {} | {} | -{} | {}x32",
                    level, contract, vmCounter, internalSteps, op,
                    gasBefore, gasCost, memWords);
        }
    }
}
