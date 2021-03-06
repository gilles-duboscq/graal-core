/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.replacements;

import com.oracle.graal.compiler.common.type.StampPair;
import com.oracle.graal.debug.GraalError;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeClass;
import com.oracle.graal.graph.spi.Canonicalizable;
import com.oracle.graal.graph.spi.CanonicalizerTool;
import com.oracle.graal.nodeinfo.NodeInfo;
import com.oracle.graal.nodes.CallTargetNode.InvokeKind;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.FrameState;
import com.oracle.graal.nodes.InvokeNode;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.spi.Lowerable;
import com.oracle.graal.nodes.spi.LoweringTool;
import com.oracle.graal.replacements.nodes.MacroStateSplitNode;

import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;

@NodeInfo
public final class ReflectionGetCallerClassNode extends MacroStateSplitNode implements Canonicalizable, Lowerable {

    public static final NodeClass<ReflectionGetCallerClassNode> TYPE = NodeClass.create(ReflectionGetCallerClassNode.class);

    public ReflectionGetCallerClassNode(InvokeKind invokeKind, ResolvedJavaMethod targetMethod, int bci, StampPair returnStamp, ValueNode... arguments) {
        super(TYPE, invokeKind, targetMethod, bci, returnStamp, arguments);
    }

    @Override
    public Node canonical(CanonicalizerTool tool) {
        ConstantNode callerClassNode = getCallerClassNode(tool.getMetaAccess(), tool.getConstantReflection());
        if (callerClassNode != null) {
            return callerClassNode;
        }
        return this;
    }

    @Override
    public void lower(LoweringTool tool) {
        ConstantNode callerClassNode = getCallerClassNode(tool.getMetaAccess(), tool.getConstantReflection());

        if (callerClassNode != null) {
            graph().replaceFixedWithFloating(this, graph().addOrUniqueWithInputs(callerClassNode));
        } else {
            InvokeNode invoke = createInvoke();
            graph().replaceFixedWithFixed(this, invoke);
            invoke.lower(tool);
        }
    }

    /**
     * If inlining is deep enough this method returns a {@link ConstantNode} of the caller class by
     * walking the the stack.
     *
     * @param metaAccess
     * @return ConstantNode of the caller class, or null
     */
    private ConstantNode getCallerClassNode(MetaAccessProvider metaAccess, ConstantReflectionProvider constantReflection) {
        // Walk back up the frame states to find the caller at the required depth.
        FrameState state = stateAfter();

        // Cf. JVM_GetCallerClass
        // NOTE: Start the loop at depth 1 because the current frame state does
        // not include the Reflection.getCallerClass() frame.
        for (int n = 1; state != null; state = state.outerFrameState(), n++) {
            HotSpotResolvedJavaMethod method = (HotSpotResolvedJavaMethod) state.method();
            switch (n) {
                case 0:
                    throw GraalError.shouldNotReachHere("current frame state does not include the Reflection.getCallerClass frame");
                case 1:
                    // Frame 0 and 1 must be caller sensitive (see JVM_GetCallerClass).
                    if (!method.isCallerSensitive()) {
                        return null;  // bail-out; let JVM_GetCallerClass do the work
                    }
                    break;
                default:
                    if (!method.ignoredBySecurityStackWalk()) {
                        // We have reached the desired frame; return the holder class.
                        HotSpotResolvedObjectType callerClass = method.getDeclaringClass();
                        return ConstantNode.forConstant(constantReflection.asJavaClass(callerClass), metaAccess);
                    }
                    break;
            }
        }
        return null;  // bail-out; let JVM_GetCallerClass do the work
    }

}
