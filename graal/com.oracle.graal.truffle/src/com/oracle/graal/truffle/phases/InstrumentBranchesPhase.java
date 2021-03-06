/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.truffle.phases;

import static com.oracle.graal.truffle.TruffleCompilerOptions.TruffleInstrumentBranchesCount;
import static com.oracle.graal.truffle.TruffleCompilerOptions.TruffleInstrumentBranchesFilter;
import static com.oracle.graal.truffle.TruffleCompilerOptions.TruffleInstrumentBranchesPerInlineSite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.compiler.common.type.TypeReference;
import com.oracle.graal.debug.MethodFilter;
import com.oracle.graal.debug.TTY;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeSourcePosition;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.calc.AddNode;
import com.oracle.graal.nodes.java.LoadIndexedNode;
import com.oracle.graal.nodes.java.StoreIndexedNode;
import com.oracle.graal.phases.BasePhase;
import com.oracle.graal.phases.tiers.HighTierContext;

import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaUtil;
import jdk.vm.ci.meta.ResolvedJavaField;
import jdk.vm.ci.meta.ResolvedJavaType;

public class InstrumentBranchesPhase extends BasePhase<HighTierContext> {

    private static final String ACCESS_TABLE_FIELD_NAME = "ACCESS_TABLE";
    static final int ACCESS_TABLE_SIZE = TruffleInstrumentBranchesCount.getValue();
    public static final long[] ACCESS_TABLE = new long[ACCESS_TABLE_SIZE];
    public static BranchInstrumentation instrumentation = new BranchInstrumentation();

    private final MethodFilter[] methodFilter;

    public InstrumentBranchesPhase() {
        String filterValue = TruffleInstrumentBranchesFilter.getValue();
        if (filterValue != null) {
            methodFilter = MethodFilter.parse(filterValue);
        } else {
            methodFilter = new MethodFilter[0];
        }
    }

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        ResolvedJavaField[] fields = context.getMetaAccess().lookupJavaType(InstrumentBranchesPhase.class).getStaticFields();
        ResolvedJavaField tableField = null;
        for (ResolvedJavaField field : fields) {
            if (field.getName().equals(ACCESS_TABLE_FIELD_NAME)) {
                tableField = field;
                break;
            }
        }
        JavaConstant tableConstant = context.getConstantReflection().readFieldValue(tableField, null);
        try {
            for (IfNode n : graph.getNodes().filter(IfNode.class)) {
                BranchInstrumentation.Point p = instrumentation.getOrCreatePoint(methodFilter, n);
                if (p != null) {
                    insertCounter(graph, tableField, tableConstant, n, p, true);
                    insertCounter(graph, tableField, tableConstant, n, p, false);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void insertCounter(StructuredGraph graph, ResolvedJavaField tableField, JavaConstant tableConstant,
                    IfNode ifNode, BranchInstrumentation.Point p, boolean isTrue) {
        assert (tableConstant != null);
        AbstractBeginNode beginNode = (isTrue) ? ifNode.trueSuccessor() : ifNode.falseSuccessor();
        TypeReference typeRef = TypeReference.createExactTrusted((ResolvedJavaType) tableField.getType());
        ConstantNode table = graph.unique(new ConstantNode(tableConstant, StampFactory.object(typeRef, true)));
        ConstantNode rawIndex = graph.unique(ConstantNode.forInt(p.getRawIndex(isTrue)));
        LoadIndexedNode load = graph.add(new LoadIndexedNode(null, table, rawIndex, JavaKind.Long));
        ConstantNode one = graph.unique(ConstantNode.forLong(1L));
        ValueNode add = graph.unique(new AddNode(load, one));
        StoreIndexedNode store = graph.add(new StoreIndexedNode(table, rawIndex, JavaKind.Long, add));

        graph.addAfterFixed(beginNode, load);
        graph.addAfterFixed(load, store);
    }

    public static class BranchInstrumentation {

        private Comparator<Map.Entry<String, Point>> entriesComparator = new Comparator<Map.Entry<String, Point>>() {
            @Override
            public int compare(Map.Entry<String, Point> x, Map.Entry<String, Point> y) {
                long diff = y.getValue().getHotness() - x.getValue().getHotness();
                if (diff < 0) {
                    return -1;
                } else if (diff == 0) {
                    return 0;
                } else {
                    return 1;
                }
            }
        };
        public Map<String, Point> pointMap = new LinkedHashMap<>();
        public int tableCount = 0;

        /*
         * Node source location is determined by its inlining chain. A flag value controls whether
         * we discriminate nodes by their inlining site, or only by the method in which they were
         * defined.
         */
        private static String filterAndEncode(MethodFilter[] methodFilter, Node ifNode) {
            NodeSourcePosition pos = ifNode.getNodeSourcePosition();
            if (pos != null) {
                if (!MethodFilter.matches(methodFilter, pos.getMethod())) {
                    return null;
                }
                if (TruffleInstrumentBranchesPerInlineSite.getValue()) {
                    StringBuilder sb = new StringBuilder();
                    while (pos != null) {
                        MetaUtil.appendLocation(sb.append("at "), pos.getMethod(), pos.getBCI());
                        pos = pos.getCaller();
                        if (pos != null) {
                            sb.append(CodeUtil.NEW_LINE);
                        }
                    }
                    return sb.toString();
                } else {
                    return MetaUtil.appendLocation(new StringBuilder(), pos.getMethod(), pos.getBCI()).toString();
                }
            } else {
                // IfNode has no position information, and is probably synthetic, so we do not
                // instrument it.
                return null;
            }
        }

        public synchronized ArrayList<String> accessTableToList() {
            return pointMap.entrySet().stream().sorted(entriesComparator).map(entry -> entry.getKey() + "\n" + entry.getValue()).collect(Collectors.toCollection(ArrayList::new));
        }

        public synchronized ArrayList<String> accessTableToHistogram() {
            long totalExecutions = pointMap.values().stream().mapToLong(v -> v.getHotness()).sum();
            return pointMap.entrySet().stream().sorted(entriesComparator).map(entry -> {
                int length = (int) ((1.0 * entry.getValue().getHotness() / totalExecutions) * 80);
                String bar = String.join("", Collections.nCopies(length, "*"));
                return String.format("%3d: %s", entry.getValue().getIndex(), bar);
            }).collect(Collectors.toCollection(ArrayList::new));
        }

        public synchronized void dumpAccessTable() {
            // Dump accumulated profiling information.
            TTY.println("Branch execution profile (sorted by hotness)");
            TTY.println("============================================");
            for (String line : accessTableToHistogram()) {
                TTY.println(line);
            }
            TTY.println();
            for (String line : accessTableToList()) {
                TTY.println(line);
                TTY.println();
            }
        }

        public synchronized Point getOrCreatePoint(MethodFilter[] methodFilter, IfNode n) {
            String key = filterAndEncode(methodFilter, n);
            if (key == null) {
                return null;
            }
            Point existing = pointMap.get(key);
            if (existing != null) {
                return existing;
            } else if (tableCount < ACCESS_TABLE.length) {
                int index = tableCount++;
                Point p = new Point(index);
                pointMap.put(key, p);
                return p;
            } else {
                if (tableCount == ACCESS_TABLE.length) {
                    TTY.println("Maximum number of branch instrumentation counters exceeded.");
                    tableCount += 1;
                }
                return null;
            }
        }

        public enum BranchState {
            NONE,
            IF,
            ELSE,
            BOTH;

            public static BranchState from(boolean ifVisited, boolean elseVisited) {
                if (ifVisited && elseVisited) {
                    return BOTH;
                } else if (ifVisited && !elseVisited) {
                    return IF;
                } else if (!ifVisited && elseVisited) {
                    return ELSE;
                } else {
                    return NONE;
                }
            }
        }

        private static class Point {
            private int index;

            Point(int index) {
                this.index = index;
            }

            public long ifVisits() {
                return ACCESS_TABLE[index * 2];
            }

            public long elseVisits() {
                return ACCESS_TABLE[index * 2 + 1];
            }

            public BranchState getBranchState() {
                return BranchState.from(ifVisits() > 0, elseVisits() > 0);
            }

            public String getCounts() {
                return "if=" + ifVisits() + "#, else=" + elseVisits() + "#";
            }

            public long getHotness() {
                return ifVisits() + elseVisits();
            }

            public int getIndex() {
                return index;
            }

            public int getRawIndex(boolean isTrue) {
                int rawIndex = index * 2;
                if (!isTrue) {
                    rawIndex += 1;
                }
                return rawIndex;
            }

            @Override
            public String toString() {
                return "[" + index + "] state = " + getBranchState() + "(" + getCounts() + ")";
            }
        }

    }
}
