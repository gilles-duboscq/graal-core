/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.compiler.common.alloc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;

import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Indent;

public final class TraceBuilderResult<T extends AbstractBlockBase<T>> {

    public abstract static class TrivialTracePredicate {
        public abstract <T extends AbstractBlockBase<T>> boolean isTrivialTrace(Trace<T> trace);
    }

    private final ArrayList<Trace<T>> traces;
    private final int[] blockToTrace;

    static <T extends AbstractBlockBase<T>> TraceBuilderResult<T> create(List<T> blocks, ArrayList<Trace<T>> traces, int[] blockToTrace, TrivialTracePredicate pred) {
        connect(traces, blockToTrace);
        ArrayList<Trace<T>> newTraces = reorderTraces(traces, blockToTrace, pred);
        TraceBuilderResult<T> traceBuilderResult = new TraceBuilderResult<>(newTraces, blockToTrace);
        traceBuilderResult.numberTraces();
        assert verify(traceBuilderResult, blocks.size());
        return traceBuilderResult;
    }

    private TraceBuilderResult(ArrayList<Trace<T>> traces, int[] blockToTrace) {
        this.traces = traces;
        this.blockToTrace = blockToTrace;
    }

    public Trace<T> getTraceForBlock(AbstractBlockBase<?> block) {
        int traceNr = blockToTrace[block.getId()];
        Trace<T> trace = traces.get(traceNr);
        assert traceNr == trace.getId() : "Trace number mismatch: " + traceNr + " vs. " + trace.getId();
        return trace;
    }

    public Trace<T> traceForBlock(AbstractBlockBase<?> block) {
        return getTraces().get(blockToTrace[block.getId()]);
    }

    public ArrayList<Trace<T>> getTraces() {
        return traces;
    }

    public boolean incomingEdges(Trace<?> trace) {
        int traceNr = trace.getId();
        Iterator<T> traceIt = getTraces().get(traceNr).getBlocks().iterator();
        return incomingEdges(traceNr, traceIt);
    }

    public boolean incomingSideEdges(Trace<?> trace) {
        int traceNr = trace.getId();
        Iterator<T> traceIt = getTraces().get(traceNr).getBlocks().iterator();
        if (!traceIt.hasNext()) {
            return false;
        }
        traceIt.next();
        return incomingEdges(traceNr, traceIt);
    }

    private boolean incomingEdges(int traceNr, Iterator<T> trace) {
        /* TODO (je): not efficient. find better solution. */
        while (trace.hasNext()) {
            T block = trace.next();
            for (T pred : block.getPredecessors()) {
                if (getTraceForBlock(pred).getId() != traceNr) {
                    return true;
                }
            }
        }
        return false;
    }

    public static <T extends AbstractBlockBase<T>> boolean verify(TraceBuilderResult<T> traceBuilderResult, int expectedLength) {
        ArrayList<Trace<T>> traces = traceBuilderResult.getTraces();
        assert verifyAllBlocksScheduled(traceBuilderResult, expectedLength) : "Not all blocks assigned to traces!";
        for (int i = 0; i < traces.size(); i++) {
            Trace<T> trace = traces.get(i);
            assert trace.getId() == i : "Trace number mismatch: " + trace.getId() + " vs. " + i;

            BitSet suxTraces = new BitSet(traces.size());
            for (Trace<T> suxTrace : trace.getSuccessors()) {
                assert !suxTraces.get(suxTrace.getId()) : "Trace twice successors " + suxTrace;
                suxTraces.set(suxTrace.getId());
            }

            T last = null;
            int blockNumber = 0;
            for (T current : trace.getBlocks()) {
                T block = current;
                assert traceBuilderResult.getTraceForBlock(block).getId() == i : "Trace number mismatch for block " + block + ": " + traceBuilderResult.getTraceForBlock(block) + " vs. " + i;
                assert last == null || Arrays.asList(current.getPredecessors()).contains(last) : "Last block (" + last + ") not a predecessor of " + current;
                assert current.getLinearScanNumber() == blockNumber : "Blocks not numbered correctly: " + current.getLinearScanNumber() + " vs. " + blockNumber;
                last = current;
                blockNumber++;
                for (T sux : block.getSuccessors()) {
                    Trace<T> suxTrace = traceBuilderResult.getTraceForBlock(sux);
                    assert suxTraces.get(suxTrace.getId()) : "Successor Trace " + suxTrace + " for block " + sux + " not in successor traces of " + trace;
                }
            }
        }
        return true;
    }

    private static <T extends AbstractBlockBase<T>> boolean verifyAllBlocksScheduled(TraceBuilderResult<T> traceBuilderResult, int expectedLength) {
        ArrayList<Trace<T>> traces = traceBuilderResult.getTraces();
        BitSet handled = new BitSet(expectedLength);
        for (Trace<T> trace : traces) {
            for (T block : trace.getBlocks()) {
                assert !handled.get(block.getId()) : "Block added twice: " + block;
                handled.set(block.getId());
            }
        }
        return handled.cardinality() == expectedLength;
    }

    private void numberTraces() {
        for (int i = 0; i < traces.size(); i++) {
            Trace<T> trace = traces.get(i);
            trace.setId(i);
            for (T block : trace.getBlocks()) {
                blockToTrace[block.getId()] = i;
            }
        }
    }

    private static <T extends AbstractBlockBase<T>> void connect(ArrayList<Trace<T>> traces, int[] blockToTrace) {
        int numTraces = traces.size();
        for (Trace<T> trace : traces) {
            BitSet added = new BitSet(numTraces);
            ArrayList<Trace<T>> successors = trace.getSuccessors();
            assert successors.size() == 0 : "Can only connect traces once!";

            for (T block : trace.getBlocks()) {
                for (T succ : block.getSuccessors()) {
                    int succId = blockToTrace[succ.getId()];
                    Trace<T> succTrace = traces.get(succId);
                    if (!added.get(succId)) {
                        added.set(succId);
                        successors.add(succTrace);
                    }
                }
            }
        }
    }

    @SuppressWarnings("try")
    private static <T extends AbstractBlockBase<T>> ArrayList<Trace<T>> reorderTraces(ArrayList<Trace<T>> traces, int[] blockToTrace, TrivialTracePredicate pred) {
        if (pred == null) {
            return traces;
        }
        try (Indent indent = Debug.logAndIndent("ReorderTrace")) {
            ArrayList<Trace<T>> newTraces = new ArrayList<>(traces.size());
            for (Trace<T> currentTrace : traces) {
                if (currentTrace != null) {
                    // add current trace
                    newTraces.add(currentTrace);
                    for (Trace<T> succTrace : currentTrace.getSuccessors()) {
                        int succTraceIndex = getTraceIndex(succTrace, blockToTrace);
                        if (getTraceIndex(currentTrace, blockToTrace) < succTraceIndex && pred.isTrivialTrace(succTrace)) {
                            //
                            int oldTraceId = succTraceIndex;
                            int newTraceId = newTraces.size();
                            Debug.log("Moving trivial trace from %d to %d", oldTraceId, newTraceId);
                            //
                            succTrace.setId(newTraceId);
                            newTraces.add(succTrace);
                            traces.set(oldTraceId, null);
                        }
                    }
                }
            }
            assert newTraces.size() == traces.size() : "Lost traces?";
            return newTraces;
        }
    }

    private static <T extends AbstractBlockBase<T>> int getTraceIndex(Trace<T> trace, int[] blockToTrace) {
        return blockToTrace[trace.getBlocks().get(0).getId()];
    }

}
