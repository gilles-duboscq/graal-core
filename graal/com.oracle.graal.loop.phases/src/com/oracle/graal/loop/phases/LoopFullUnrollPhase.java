/*
 * Copyright (c) 2012, 2012, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.loop.phases;

import java.util.LinkedList;
import java.util.List;

import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.DebugCounter;
import com.oracle.graal.graph.Node;
import com.oracle.graal.loop.LoopEx;
import com.oracle.graal.loop.LoopPolicies;
import com.oracle.graal.loop.LoopsData;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.calc.IntegerEqualsNode;
import com.oracle.graal.nodes.calc.IntegerLessThanNode;
import com.oracle.graal.phases.common.CanonicalizerPhase;
import com.oracle.graal.phases.tiers.PhaseContext;

public class LoopFullUnrollPhase extends LoopPhase<LoopPolicies> {

    private static final DebugCounter FULLY_UNROLLED_LOOPS = Debug.counter("FullUnrolls");
    private final CanonicalizerPhase canonicalizer;

    public LoopFullUnrollPhase(CanonicalizerPhase canonicalizer, LoopPolicies policies) {
        super(policies);
        this.canonicalizer = canonicalizer;
    }

    @Override
    protected void run(StructuredGraph graph, PhaseContext context) {
        if (graph.hasLoops()) {
            boolean peeled;
            boolean integerComparesCleaned = false;
            do {
                peeled = false;
                final LoopsData dataCounted = new LoopsData(graph);
                dataCounted.detectedCountedLoops();
                for (LoopEx loop : dataCounted.countedLoops()) {
                    if (getPolicies().shouldFullUnroll(loop)) {
                        if (!integerComparesCleaned) {
                            cleanupIntegerCompares(graph, context);
                            integerComparesCleaned = true;
                        }
                        Debug.log("FullUnroll %s", loop);
                        LoopTransformations.fullUnroll(loop, context, canonicalizer);
                        FULLY_UNROLLED_LOOPS.increment();
                        Debug.dump(Debug.INFO_LOG_LEVEL, graph, "FullUnroll %s", loop);
                        peeled = true;
                        break;
                    }
                }
                dataCounted.deleteUnusedNodes();
            } while (peeled);
        }
    }

    private void cleanupIntegerCompares(StructuredGraph graph, PhaseContext context) {
        List<Node> integerCompares = new LinkedList<>();
        for (Node n : graph.getNodes(IntegerEqualsNode.TYPE)) {
            integerCompares.add(n);
        }
        for (Node n : graph.getNodes(IntegerLessThanNode.TYPE)) {
            integerCompares.add(n);
        }
        if (!integerCompares.isEmpty()) {
            canonicalizer.applyIncremental(graph, context, integerCompares);
        }
    }
}
