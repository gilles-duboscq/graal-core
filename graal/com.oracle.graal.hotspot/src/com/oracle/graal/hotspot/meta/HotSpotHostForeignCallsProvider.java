/*
 * Copyright (c) 2013, 2016, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.hotspot.meta;

import static com.oracle.graal.compiler.target.Backend.ARITHMETIC_COS;
import static com.oracle.graal.compiler.target.Backend.ARITHMETIC_EXP;
import static com.oracle.graal.compiler.target.Backend.ARITHMETIC_LOG;
import static com.oracle.graal.compiler.target.Backend.ARITHMETIC_LOG10;
import static com.oracle.graal.compiler.target.Backend.ARITHMETIC_POW;
import static com.oracle.graal.compiler.target.Backend.ARITHMETIC_SIN;
import static com.oracle.graal.compiler.target.Backend.ARITHMETIC_TAN;
import static com.oracle.graal.hotspot.HotSpotBackend.DECRYPT;
import static com.oracle.graal.hotspot.HotSpotBackend.DECRYPT_BLOCK;
import static com.oracle.graal.hotspot.HotSpotBackend.DECRYPT_BLOCK_WITH_ORIGINAL_KEY;
import static com.oracle.graal.hotspot.HotSpotBackend.DECRYPT_WITH_ORIGINAL_KEY;
import static com.oracle.graal.hotspot.HotSpotBackend.ENCRYPT;
import static com.oracle.graal.hotspot.HotSpotBackend.ENCRYPT_BLOCK;
import static com.oracle.graal.hotspot.HotSpotBackend.EXCEPTION_HANDLER;
import static com.oracle.graal.hotspot.HotSpotBackend.FETCH_UNROLL_INFO;
import static com.oracle.graal.hotspot.HotSpotBackend.IC_MISS_HANDLER;
import static com.oracle.graal.hotspot.HotSpotBackend.NEW_ARRAY;
import static com.oracle.graal.hotspot.HotSpotBackend.NEW_INSTANCE;
import static com.oracle.graal.hotspot.HotSpotBackend.NEW_MULTI_ARRAY;
import static com.oracle.graal.hotspot.HotSpotBackend.UNCOMMON_TRAP;
import static com.oracle.graal.hotspot.HotSpotBackend.UNPACK_FRAMES;
import static com.oracle.graal.hotspot.HotSpotBackend.UNWIND_EXCEPTION_TO_CALLER;
import static com.oracle.graal.hotspot.HotSpotBackend.VM_ERROR;
import static com.oracle.graal.hotspot.HotSpotBackend.Options.PreferGraalStubs;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect.DESTROYS_REGISTERS;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.RegisterEffect.PRESERVES_REGISTERS;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition.LEAF;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition.LEAF_NOFP;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition.SAFEPOINT;
import static com.oracle.graal.hotspot.HotSpotForeignCallLinkage.Transition.STACK_INSPECTABLE_LEAF;
import static com.oracle.graal.hotspot.HotSpotHostBackend.DEOPTIMIZATION_HANDLER;
import static com.oracle.graal.hotspot.HotSpotHostBackend.UNCOMMON_TRAP_HANDLER;
import static com.oracle.graal.hotspot.meta.DefaultHotSpotLoweringProvider.RuntimeCalls.CREATE_ARRAY_STORE_EXCEPTION;
import static com.oracle.graal.hotspot.meta.DefaultHotSpotLoweringProvider.RuntimeCalls.CREATE_CLASS_CAST_EXCEPTION;
import static com.oracle.graal.hotspot.meta.DefaultHotSpotLoweringProvider.RuntimeCalls.CREATE_NULL_POINTER_EXCEPTION;
import static com.oracle.graal.hotspot.meta.DefaultHotSpotLoweringProvider.RuntimeCalls.CREATE_OUT_OF_BOUNDS_EXCEPTION;
import static com.oracle.graal.hotspot.replacements.AssertionSnippets.ASSERTION_VM_MESSAGE_C;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.MARK_WORD_LOCATION;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.TLAB_END_LOCATION;
import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.TLAB_TOP_LOCATION;
import static com.oracle.graal.hotspot.replacements.MonitorSnippets.MONITORENTER;
import static com.oracle.graal.hotspot.replacements.MonitorSnippets.MONITOREXIT;
import static com.oracle.graal.hotspot.replacements.NewObjectSnippets.DYNAMIC_NEW_ARRAY;
import static com.oracle.graal.hotspot.replacements.NewObjectSnippets.DYNAMIC_NEW_INSTANCE;
import static com.oracle.graal.hotspot.replacements.NewObjectSnippets.INIT_LOCATION;
import static com.oracle.graal.hotspot.replacements.SystemSubstitutions.JAVA_TIME_MILLIS;
import static com.oracle.graal.hotspot.replacements.SystemSubstitutions.JAVA_TIME_NANOS;
import static com.oracle.graal.hotspot.replacements.ThreadSubstitutions.THREAD_IS_INTERRUPTED;
import static com.oracle.graal.hotspot.replacements.WriteBarrierSnippets.G1WBPOSTCALL;
import static com.oracle.graal.hotspot.replacements.WriteBarrierSnippets.G1WBPRECALL;
import static com.oracle.graal.hotspot.replacements.WriteBarrierSnippets.VALIDATE_OBJECT;
import static com.oracle.graal.hotspot.stubs.ExceptionHandlerStub.EXCEPTION_HANDLER_FOR_PC;
import static com.oracle.graal.hotspot.stubs.NewArrayStub.NEW_ARRAY_C;
import static com.oracle.graal.hotspot.stubs.NewInstanceStub.NEW_INSTANCE_C;
import static com.oracle.graal.hotspot.stubs.StubUtil.VM_MESSAGE_C;
import static com.oracle.graal.hotspot.stubs.UnwindExceptionToCallerStub.EXCEPTION_HANDLER_FOR_RETURN_ADDRESS;
import static com.oracle.graal.nodes.NamedLocationIdentity.any;
import static com.oracle.graal.nodes.java.ForeignCallDescriptors.REGISTER_FINALIZER;
import static com.oracle.graal.replacements.Log.LOG_OBJECT;
import static com.oracle.graal.replacements.Log.LOG_PRIMITIVE;
import static com.oracle.graal.replacements.Log.LOG_PRINTF;
import static jdk.vm.ci.hotspot.HotSpotCallingConventionType.NativeCall;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import com.oracle.graal.compiler.common.LocationIdentity;
import com.oracle.graal.compiler.common.spi.ForeignCallDescriptor;
import com.oracle.graal.compiler.common.spi.ForeignCallsProvider;
import com.oracle.graal.debug.GraalError;
import com.oracle.graal.hotspot.GraalHotSpotVMConfig;
import com.oracle.graal.hotspot.HotSpotForeignCallLinkage;
import com.oracle.graal.hotspot.HotSpotGraalRuntimeProvider;
import com.oracle.graal.hotspot.stubs.ArrayStoreExceptionStub;
import com.oracle.graal.hotspot.stubs.ClassCastExceptionStub;
import com.oracle.graal.hotspot.stubs.CreateExceptionStub;
import com.oracle.graal.hotspot.stubs.ExceptionHandlerStub;
import com.oracle.graal.hotspot.stubs.NewArrayStub;
import com.oracle.graal.hotspot.stubs.NewInstanceStub;
import com.oracle.graal.hotspot.stubs.NullPointerExceptionStub;
import com.oracle.graal.hotspot.stubs.OutOfBoundsExceptionStub;
import com.oracle.graal.hotspot.stubs.Stub;
import com.oracle.graal.hotspot.stubs.UnwindExceptionToCallerStub;
import com.oracle.graal.hotspot.stubs.VerifyOopStub;
import com.oracle.graal.nodes.NamedLocationIdentity;
import com.oracle.graal.word.Word;
import com.oracle.graal.word.WordTypes;

import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.hotspot.HotSpotJVMCIRuntimeProvider;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;

/**
 * HotSpot implementation of {@link ForeignCallsProvider}.
 */
public abstract class HotSpotHostForeignCallsProvider extends HotSpotForeignCallsProviderImpl {

    public HotSpotHostForeignCallsProvider(HotSpotJVMCIRuntimeProvider jvmciRuntime, HotSpotGraalRuntimeProvider runtime, MetaAccessProvider metaAccess, CodeCacheProvider codeCache,
                    WordTypes wordTypes) {
        super(jvmciRuntime, runtime, metaAccess, codeCache, wordTypes);
    }

    protected static void link(Stub stub) {
        stub.getLinkage().setCompiledStub(stub);
    }

    public static ForeignCallDescriptor lookupCheckcastArraycopyDescriptor(boolean uninit) {
        return checkcastArraycopyDescriptors[uninit ? 1 : 0];
    }

    public static ForeignCallDescriptor lookupArraycopyDescriptor(JavaKind kind, boolean aligned, boolean disjoint, boolean uninit, boolean killAny) {
        if (uninit) {
            assert kind == JavaKind.Object;
            assert !killAny : "unsupported";
            return uninitObjectArraycopyDescriptors[aligned ? 1 : 0][disjoint ? 1 : 0];
        }
        if (killAny) {
            assert kind == JavaKind.Object;
            return objectArraycopyDescriptorsKillAny[aligned ? 1 : 0][disjoint ? 1 : 0];
        }
        return arraycopyDescriptors[aligned ? 1 : 0][disjoint ? 1 : 0].get(kind);
    }

    @SuppressWarnings({"unchecked"}) private static final EnumMap<JavaKind, ForeignCallDescriptor>[][] arraycopyDescriptors = (EnumMap<JavaKind, ForeignCallDescriptor>[][]) new EnumMap<?, ?>[2][2];

    private static final ForeignCallDescriptor[][] uninitObjectArraycopyDescriptors = new ForeignCallDescriptor[2][2];
    private static final ForeignCallDescriptor[] checkcastArraycopyDescriptors = new ForeignCallDescriptor[2];
    private static ForeignCallDescriptor[][] objectArraycopyDescriptorsKillAny = new ForeignCallDescriptor[2][2];

    static {
        // Populate the EnumMap instances
        for (int i = 0; i < arraycopyDescriptors.length; i++) {
            for (int j = 0; j < arraycopyDescriptors[i].length; j++) {
                arraycopyDescriptors[i][j] = new EnumMap<>(JavaKind.class);
            }
        }
    }

    private void registerArraycopyDescriptor(Map<Long, ForeignCallDescriptor> descMap, JavaKind kind, boolean aligned, boolean disjoint, boolean uninit, boolean killAny, long routine) {
        ForeignCallDescriptor desc = descMap.get(routine);
        if (desc == null) {
            desc = buildDescriptor(kind, aligned, disjoint, uninit, killAny, routine);
            descMap.put(routine, desc);
        }
        if (uninit) {
            assert kind == JavaKind.Object;
            uninitObjectArraycopyDescriptors[aligned ? 1 : 0][disjoint ? 1 : 0] = desc;
        } else {
            arraycopyDescriptors[aligned ? 1 : 0][disjoint ? 1 : 0].put(kind, desc);
        }
    }

    private ForeignCallDescriptor buildDescriptor(JavaKind kind, boolean aligned, boolean disjoint, boolean uninit, boolean killAny, long routine) {
        assert !killAny || kind == JavaKind.Object;
        String name = kind + (aligned ? "Aligned" : "") + (disjoint ? "Disjoint" : "") + (uninit ? "Uninit" : "") + "Arraycopy" + (killAny ? "KillAny" : "");
        ForeignCallDescriptor desc = new ForeignCallDescriptor(name, void.class, Word.class, Word.class, Word.class);
        LocationIdentity killed = killAny ? LocationIdentity.any() : NamedLocationIdentity.getArrayLocation(kind);
        registerForeignCall(desc, routine, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, killed);
        return desc;
    }

    private void registerCheckcastArraycopyDescriptor(boolean uninit, long routine) {
        String name = "Object" + (uninit ? "Uninit" : "") + "Checkcast";
        // Input:
        // c_rarg0 - source array address
        // c_rarg1 - destination array address
        // c_rarg2 - element count, treated as ssize_t, can be zero
        // c_rarg3 - size_t ckoff (super_check_offset)
        // c_rarg4 - oop ckval (super_klass)
        // return: 0 = success, n = number of copied elements xor'd with -1.
        ForeignCallDescriptor desc = new ForeignCallDescriptor(name, int.class, Word.class, Word.class, Word.class, Word.class, Word.class);
        LocationIdentity killed = NamedLocationIdentity.getArrayLocation(JavaKind.Object);
        registerForeignCall(desc, routine, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, killed);
        checkcastArraycopyDescriptors[uninit ? 1 : 0] = desc;
    }

    private void registerArrayCopy(JavaKind kind, long routine, long alignedRoutine, long disjointRoutine, long alignedDisjointRoutine) {
        registerArrayCopy(kind, routine, alignedRoutine, disjointRoutine, alignedDisjointRoutine, false);
    }

    private void registerArrayCopy(JavaKind kind, long routine, long alignedRoutine, long disjointRoutine, long alignedDisjointRoutine, boolean uninit) {
        /*
         * Sometimes the same function is used for multiple cases so share them when that's the case
         * but only within the same Kind. For instance short and char are the same copy routines but
         * they kill different memory so they still have to be distinct.
         */
        Map<Long, ForeignCallDescriptor> descMap = new HashMap<>();
        registerArraycopyDescriptor(descMap, kind, false, false, uninit, false, routine);
        registerArraycopyDescriptor(descMap, kind, true, false, uninit, false, alignedRoutine);
        registerArraycopyDescriptor(descMap, kind, false, true, uninit, false, disjointRoutine);
        registerArraycopyDescriptor(descMap, kind, true, true, uninit, false, alignedDisjointRoutine);

        if (kind == JavaKind.Object && !uninit) {
            objectArraycopyDescriptorsKillAny[0][0] = buildDescriptor(kind, false, false, uninit, true, routine);
            objectArraycopyDescriptorsKillAny[1][0] = buildDescriptor(kind, true, false, uninit, true, alignedRoutine);
            objectArraycopyDescriptorsKillAny[0][1] = buildDescriptor(kind, false, true, uninit, true, disjointRoutine);
            objectArraycopyDescriptorsKillAny[1][1] = buildDescriptor(kind, true, true, uninit, true, alignedDisjointRoutine);
        }
    }

    public void initialize(HotSpotProviders providers) {
        GraalHotSpotVMConfig c = runtime.getVMConfig();
        if (!PreferGraalStubs.getValue()) {
            registerForeignCall(DEOPTIMIZATION_HANDLER, c.handleDeoptStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
            registerForeignCall(UNCOMMON_TRAP_HANDLER, c.uncommonTrapStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        }
        registerForeignCall(IC_MISS_HANDLER, c.inlineCacheMissStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);

        registerForeignCall(JAVA_TIME_MILLIS, c.javaTimeMillisAddress, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(JAVA_TIME_NANOS, c.javaTimeNanosAddress, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_SIN, c.arithmeticSinAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_COS, c.arithmeticCosAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_TAN, c.arithmeticTanAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_EXP, c.arithmeticExpAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_LOG, c.arithmeticLogAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_LOG10, c.arithmeticLog10Address, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ARITHMETIC_POW, c.arithmeticPowAddress, NativeCall, DESTROYS_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(LOAD_AND_CLEAR_EXCEPTION, c.loadAndClearExceptionAddress, NativeCall, DESTROYS_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, any());

        registerForeignCall(EXCEPTION_HANDLER_FOR_PC, c.exceptionHandlerForPcAddress, NativeCall, DESTROYS_REGISTERS, SAFEPOINT, REEXECUTABLE, any());
        registerForeignCall(EXCEPTION_HANDLER_FOR_RETURN_ADDRESS, c.exceptionHandlerForReturnAddressAddress, NativeCall, DESTROYS_REGISTERS, SAFEPOINT, REEXECUTABLE, any());
        registerForeignCall(FETCH_UNROLL_INFO, c.deoptimizationFetchUnrollInfo, NativeCall, DESTROYS_REGISTERS, SAFEPOINT, REEXECUTABLE, any());
        registerForeignCall(NEW_ARRAY_C, c.newArrayAddress, NativeCall, DESTROYS_REGISTERS, SAFEPOINT, REEXECUTABLE, any());
        registerForeignCall(NEW_INSTANCE_C, c.newInstanceAddress, NativeCall, DESTROYS_REGISTERS, SAFEPOINT, REEXECUTABLE, any());
        registerForeignCall(UNCOMMON_TRAP, c.deoptimizationUncommonTrap, NativeCall, DESTROYS_REGISTERS, SAFEPOINT, NOT_REEXECUTABLE, any());

        /*
         * We cannot use LEAF_SP here because on some architectures we have to align the stack
         * manually before calling into the VM. See {@link
         * AMD64HotSpotEnterUnpackFramesStackFrameOp#emitCode}.
         */
        registerForeignCall(UNPACK_FRAMES, c.deoptimizationUnpackFrames, NativeCall, DESTROYS_REGISTERS, LEAF, NOT_REEXECUTABLE, any());

        CreateExceptionStub.registerForeignCalls(c, this);

        /*
         * This message call is registered twice, where the second one must only be used for calls
         * that do not return, i.e., that exit the VM.
         */
        registerForeignCall(VM_MESSAGE_C, c.vmMessageAddress, NativeCall, DESTROYS_REGISTERS, SAFEPOINT, REEXECUTABLE, NO_LOCATIONS);
        registerForeignCall(ASSERTION_VM_MESSAGE_C, c.vmMessageAddress, NativeCall, PRESERVES_REGISTERS, LEAF, REEXECUTABLE, NO_LOCATIONS);

        link(new NewInstanceStub(providers, registerStubCall(NEW_INSTANCE, REEXECUTABLE, SAFEPOINT, INIT_LOCATION, TLAB_TOP_LOCATION, TLAB_END_LOCATION)));
        link(new NewArrayStub(providers, registerStubCall(NEW_ARRAY, REEXECUTABLE, SAFEPOINT, INIT_LOCATION, TLAB_TOP_LOCATION, TLAB_END_LOCATION)));
        link(new ExceptionHandlerStub(providers, foreignCalls.get(EXCEPTION_HANDLER)));
        link(new UnwindExceptionToCallerStub(providers, registerStubCall(UNWIND_EXCEPTION_TO_CALLER, NOT_REEXECUTABLE, SAFEPOINT, any())));
        link(new VerifyOopStub(providers, registerStubCall(VERIFY_OOP, REEXECUTABLE, LEAF_NOFP, NO_LOCATIONS)));
        link(new ArrayStoreExceptionStub(providers, registerStubCall(CREATE_ARRAY_STORE_EXCEPTION, REEXECUTABLE, SAFEPOINT, any())));
        link(new ClassCastExceptionStub(providers, registerStubCall(CREATE_CLASS_CAST_EXCEPTION, REEXECUTABLE, SAFEPOINT, any())));
        link(new NullPointerExceptionStub(providers, registerStubCall(CREATE_NULL_POINTER_EXCEPTION, REEXECUTABLE, SAFEPOINT, any())));
        link(new OutOfBoundsExceptionStub(providers, registerStubCall(CREATE_OUT_OF_BOUNDS_EXCEPTION, REEXECUTABLE, SAFEPOINT, any())));

        linkForeignCall(providers, IDENTITY_HASHCODE, c.identityHashCodeAddress, PREPEND_THREAD, SAFEPOINT, NOT_REEXECUTABLE, MARK_WORD_LOCATION);
        linkForeignCall(providers, REGISTER_FINALIZER, c.registerFinalizerAddress, PREPEND_THREAD, SAFEPOINT, NOT_REEXECUTABLE, any());
        linkForeignCall(providers, MONITORENTER, c.monitorenterAddress, PREPEND_THREAD, SAFEPOINT, NOT_REEXECUTABLE, any());
        linkForeignCall(providers, MONITOREXIT, c.monitorexitAddress, PREPEND_THREAD, STACK_INSPECTABLE_LEAF, NOT_REEXECUTABLE, any());
        linkForeignCall(providers, NEW_MULTI_ARRAY, c.newMultiArrayAddress, PREPEND_THREAD, SAFEPOINT, REEXECUTABLE, INIT_LOCATION, TLAB_TOP_LOCATION, TLAB_END_LOCATION);
        linkForeignCall(providers, DYNAMIC_NEW_ARRAY, c.dynamicNewArrayAddress, PREPEND_THREAD, SAFEPOINT, REEXECUTABLE, INIT_LOCATION);
        linkForeignCall(providers, DYNAMIC_NEW_INSTANCE, c.dynamicNewInstanceAddress, PREPEND_THREAD, SAFEPOINT, REEXECUTABLE, INIT_LOCATION);
        linkForeignCall(providers, LOG_PRINTF, c.logPrintfAddress, PREPEND_THREAD, LEAF, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, LOG_OBJECT, c.logObjectAddress, PREPEND_THREAD, LEAF, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, LOG_PRIMITIVE, c.logPrimitiveAddress, PREPEND_THREAD, LEAF, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, VM_ERROR, c.vmErrorAddress, PREPEND_THREAD, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, OSR_MIGRATION_END, c.osrMigrationEndAddress, DONT_PREPEND_THREAD, LEAF_NOFP, NOT_REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, G1WBPRECALL, c.writeBarrierPreAddress, PREPEND_THREAD, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, G1WBPOSTCALL, c.writeBarrierPostAddress, PREPEND_THREAD, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);
        linkForeignCall(providers, VALIDATE_OBJECT, c.validateObject, PREPEND_THREAD, LEAF_NOFP, REEXECUTABLE, NO_LOCATIONS);

        // Cannot be a leaf as VM acquires Thread_lock which requires thread_in_vm state
        linkForeignCall(providers, THREAD_IS_INTERRUPTED, c.threadIsInterruptedAddress, PREPEND_THREAD, SAFEPOINT, NOT_REEXECUTABLE, any());

        linkForeignCall(providers, TEST_DEOPTIMIZE_CALL_INT, c.testDeoptimizeCallInt, PREPEND_THREAD, SAFEPOINT, REEXECUTABLE, any());

        registerArrayCopy(JavaKind.Byte, c.jbyteArraycopy, c.jbyteAlignedArraycopy, c.jbyteDisjointArraycopy, c.jbyteAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Boolean, c.jbyteArraycopy, c.jbyteAlignedArraycopy, c.jbyteDisjointArraycopy, c.jbyteAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Char, c.jshortArraycopy, c.jshortAlignedArraycopy, c.jshortDisjointArraycopy, c.jshortAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Short, c.jshortArraycopy, c.jshortAlignedArraycopy, c.jshortDisjointArraycopy, c.jshortAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Int, c.jintArraycopy, c.jintAlignedArraycopy, c.jintDisjointArraycopy, c.jintAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Float, c.jintArraycopy, c.jintAlignedArraycopy, c.jintDisjointArraycopy, c.jintAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Long, c.jlongArraycopy, c.jlongAlignedArraycopy, c.jlongDisjointArraycopy, c.jlongAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Double, c.jlongArraycopy, c.jlongAlignedArraycopy, c.jlongDisjointArraycopy, c.jlongAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Object, c.oopArraycopy, c.oopAlignedArraycopy, c.oopDisjointArraycopy, c.oopAlignedDisjointArraycopy);
        registerArrayCopy(JavaKind.Object, c.oopArraycopyUninit, c.oopAlignedArraycopyUninit, c.oopDisjointArraycopyUninit, c.oopAlignedDisjointArraycopyUninit, true);

        registerCheckcastArraycopyDescriptor(true, c.checkcastArraycopyUninit);
        registerCheckcastArraycopyDescriptor(false, c.checkcastArraycopy);

        if (c.useAESIntrinsics) {
            /*
             * When the java.ext.dirs property is modified then the crypto classes might not be
             * found. If that's the case we ignore the ClassNotFoundException and continue since we
             * cannot replace a non-existing method anyway.
             */
            try {
                // These stubs do callee saving
                registerForeignCall(ENCRYPT_BLOCK, c.aescryptEncryptBlockStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, NamedLocationIdentity.getArrayLocation(JavaKind.Byte));
                registerForeignCall(DECRYPT_BLOCK, c.aescryptDecryptBlockStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE, NamedLocationIdentity.getArrayLocation(JavaKind.Byte));
                registerForeignCall(DECRYPT_BLOCK_WITH_ORIGINAL_KEY, c.aescryptDecryptBlockStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE,
                                NamedLocationIdentity.getArrayLocation(JavaKind.Byte));
            } catch (GraalError e) {
                if (!(e.getCause() instanceof ClassNotFoundException)) {
                    throw e;
                }
            }
            try {
                // These stubs do callee saving
                registerForeignCall(ENCRYPT, c.cipherBlockChainingEncryptAESCryptStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE,
                                NamedLocationIdentity.getArrayLocation(JavaKind.Byte));
                registerForeignCall(DECRYPT, c.cipherBlockChainingDecryptAESCryptStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE,
                                NamedLocationIdentity.getArrayLocation(JavaKind.Byte));
                registerForeignCall(DECRYPT_WITH_ORIGINAL_KEY, c.cipherBlockChainingDecryptAESCryptStub, NativeCall, PRESERVES_REGISTERS, LEAF_NOFP, NOT_REEXECUTABLE,
                                NamedLocationIdentity.getArrayLocation(JavaKind.Byte));
            } catch (GraalError e) {
                if (!(e.getCause() instanceof ClassNotFoundException)) {
                    throw e;
                }
            }
        }
    }

    public HotSpotForeignCallLinkage getForeignCall(ForeignCallDescriptor descriptor) {
        assert foreignCalls != null : descriptor;
        return foreignCalls.get(descriptor);
    }
}
