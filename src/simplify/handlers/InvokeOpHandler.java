package simplify.handlers;

import gnu.trove.list.TIntList;

import java.util.Arrays;
import java.util.logging.Logger;

import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction35c;
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.util.ReferenceUtil;

import simplify.Main;
import simplify.MethodReflector;
import simplify.emulate.MethodEmulator;
import simplify.vm.ContextGraph;
import simplify.vm.MethodContext;
import simplify.vm.RegisterStore;
import simplify.vm.UnknownValue;
import simplify.vm.VirtualMachine;

public class InvokeOpHandler extends OpHandler {

    private static final Logger log = Logger.getLogger(Main.class.getSimpleName());

    static InvokeOpHandler create(Instruction instruction, int address, VirtualMachine vm) {
        int childAddress = address + instruction.getCodeUnits();
        String opName = instruction.getOpcode().name;

        int[] registers = null;
        MethodReference methodReference = null;
        if (opName.contains("/range")) {
            Instruction3rc instr = (Instruction3rc) instruction;
            int registerCount = instr.getRegisterCount();
            int start = instr.getStartRegister();
            int end = start + registerCount;

            registers = new int[registerCount];
            for (int i = start; i < end; i++) {
                registers[i - start] = i;
            }

            methodReference = (MethodReference) instr.getReference();
        } else {
            Instruction35c instr = (Instruction35c) instruction;
            int registerCount = instr.getRegisterCount();

            registers = new int[registerCount];
            switch (registerCount) {
            case 5:
                registers[4] = instr.getRegisterG();
            case 4:
                registers[3] = instr.getRegisterF();
            case 3:
                registers[2] = instr.getRegisterE();
            case 2:
                registers[1] = instr.getRegisterD();
            case 1:
                registers[0] = instr.getRegisterC();
                break;
            }

            methodReference = (MethodReference) instr.getReference();
        }

        return new InvokeOpHandler(address, opName, childAddress, methodReference, registers, vm);
    }

    private final int address;
    private final String opName;
    private final int childAddress;
    private final MethodReference methodReference;
    private final int[] registers;
    private final VirtualMachine vm;
    private final boolean isStatic;

    private InvokeOpHandler(int address, String opName, int childAddress, MethodReference methodReference,
                    int[] registers, VirtualMachine vm) {
        this.address = address;
        this.opName = opName;
        this.childAddress = childAddress;
        this.methodReference = methodReference;
        this.registers = registers;
        this.vm = vm;
        isStatic = opName.contains("-static");
    }

    @Override
    public int[] execute(MethodContext mctx) {
        String methodDescriptor = ReferenceUtil.getMethodDescriptor(methodReference);
        boolean returnsVoid = methodReference.getReturnType().equals("V");
        if (vm.isMethodDefined(methodDescriptor)) {
            // VM has this method, so it knows how to execute it.
            MethodContext calleeContext = vm.getInstructionGraph(methodDescriptor).getRootContext();
            // if (isStatic) {
            // // Exclude first register since it's an instance reference and will already be a parameter.
            // int[] registers = Arrays.copyOfRange(this.registers, 1, this.registers.length);
            // } else {
            // Object instance = mctx.getRegisterValue(registers[0], address)
            // mctx.getRegister(mctx.getParameterStart() - 1, )
            // }

            if (!isStatic) {
                // First parameter is an instance reference, which is right before the parameter register range starts.
                RegisterStore instance = mctx.getRegister(registers[0], address);
                calleeContext.pokeRegister(calleeContext.getParameterStart() - 1, instance);

                int[] registers = Arrays.copyOfRange(this.registers, 1, this.registers.length);
                addCalleeParameters(calleeContext, mctx, registers, address);
            } else {
                addCalleeParameters(calleeContext, mctx, registers, address);
            }

            ContextGraph graph = vm.execute(methodDescriptor, calleeContext);
            if (graph == null) {
                // Couldn't execute the method. Maybe node visits or call depth exceeded?
                log.info("Problem executing " + methodDescriptor + ", propigating ambiguity.");
                assumeMaximumUnknown(vm, mctx, registers, methodReference.getReturnType());
                return getPossibleChildren();
            }

            // TODO: fix this
            // updateInstanceAndMutableArguments(vm, mctx, graph, isStatic);

            if (!returnsVoid) {
                TIntList terminating = graph.getConnectedTerminatingAddresses();
                RegisterStore registerStore = graph.getConsensus(terminating, MethodContext.ReturnRegister);
                mctx.setResultRegister(registerStore);
            }
        } else {
            MethodContext calleeContext = buildCalleeContext(mctx, registers, address);
            boolean allArgumentsKnown = allArgumentsKnown(calleeContext);
            if (allArgumentsKnown && MethodEmulator.canEmulate(methodDescriptor)) {
                MethodEmulator.emulate(calleeContext, methodDescriptor);
            } else if (allArgumentsKnown && MethodReflector.canReflect(methodDescriptor)) {
                MethodReflector reflector = new MethodReflector(methodReference, isStatic);
                reflector.reflect(calleeContext); // player play
            } else {
                // Method not found and either all arguments are not known, couldn't emulate or reflect
                log.fine("Unknown argument or couldn't find/emulate/reflect " + methodDescriptor
                                + " so propigating ambiguity.");
                assumeMaximumUnknown(vm, mctx, registers, methodReference.getReturnType());
                return getPossibleChildren();
            }

            // TODO: fix these
            // updateInstanceAndMutableArguments(vm, mctx, calleeContext, isStatic);

            if (!returnsVoid) {
                RegisterStore returnRegister = calleeContext.getReturnRegister();
                mctx.setResultRegister(returnRegister);
            }
        }

        return getPossibleChildren();
    }

    private static void updateInstanceAndMutableArguments(VirtualMachine vm, MethodContext callerContext,
                    MethodContext calleeContext, boolean isStatic) {
        if (!isStatic) {
            RegisterStore registerStore = callerContext.peekRegister(0);
            Object value = calleeContext.peekRegisterValue(0);
            registerStore.setValue(value);
            log.fine("updating instance value: " + registerStore);
        }

        for (int i = 0; i < callerContext.getRegisterCount(); i++) {
            RegisterStore registerStore = callerContext.peekRegister(i);
            if (!vm.isImmutableClass(registerStore.getType())) {
                Object value = calleeContext.peekRegisterValue(i);
                registerStore.setValue(value);
                log.fine(registerStore.getType() + " is mutable, updating with callee value = " + registerStore);
            }
        }

    }

    private static void updateInstanceAndMutableArguments(VirtualMachine vm, MethodContext callerContext,
                    ContextGraph graph, boolean isStatic) {
        if (!isStatic) {
            RegisterStore registerStore = callerContext.peekRegister(0);
            Object value = graph.getConsensus(0, 0).getValue();
            registerStore.setValue(value);
            log.fine("updating instance value: " + registerStore);
        }

        for (int i = 0; i < callerContext.getRegisterCount(); i++) {
            RegisterStore registerStore = callerContext.peekRegister(i);
            if (!vm.isImmutableClass(registerStore.getType())) {
                Object value = graph.getConsensus(0, i).getValue();
                registerStore.setValue(value);
                log.fine(registerStore.getType() + " is mutable, updating with callee value = " + registerStore);
            }
        }
    }

    private static boolean allArgumentsKnown(MethodContext mctx) {
        for (int i = 0; i < mctx.getRegisterCount(); i++) {
            RegisterStore registerStore = mctx.peekRegister(i);
            // TODO: consider creating iterator to handle these cases
            if (registerStore.getType().equals("J")) {
                // This register index and the next both refer to this variable.
                i++;
            }

            if (registerStore.getValue() instanceof UnknownValue) {
                return false;
            }
        }

        return true;
    }

    private static void assumeMaximumUnknown(VirtualMachine vm, MethodContext callerContext, int[] registers,
                    String returnType) {
        for (int i = 0; i < registers.length; i++) {
            int register = registers[i];
            String className = callerContext.peekRegisterType(register);
            if (vm.isImmutableClass(className)) {
                if (className.equals("J")) {
                    i++;
                }

                log.fine(className + " is immutable");
                continue;
            }

            log.fine(className + " is mutable and passed into strange method, marking unknown");
            RegisterStore registerStore = new RegisterStore(className, new UnknownValue());
            callerContext.pokeRegister(register, registerStore);
        }

        if (!returnType.equals("V")) {
            callerContext.setResultRegister(new RegisterStore(returnType, new UnknownValue()));
        }
    }

    private static void addCalleeParameters(MethodContext calleeContext, MethodContext callerContext, int[] registers,
                    int address) {
        for (int i = 0; i < registers.length; i++) {
            int register = registers[i];
            // Passing actual value references since they'll be updated correctly by the JVM.
            RegisterStore registerStore = callerContext.getRegister(register, address);
            calleeContext.setParameter(i, registerStore);

            if (registerStore.getType().equals("J")) {
                // This register index and the next both refer to this variable.
                i++;
            }
        }
    }

    private static MethodContext buildCalleeContext(MethodContext callerContext, int[] registers, int address) {
        int parameterCount = registers.length;
        MethodContext calleeContext = new MethodContext(parameterCount, parameterCount,
                        callerContext.getCallDepth() + 1);

        addCalleeParameters(calleeContext, callerContext, registers, address);

        return calleeContext;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(opName);

        sb.append(" {");
        if (opName.contains("/range")) {
            sb.append("r").append(registers[0]).append(" .. r").append(registers[registers.length - 1]);
        } else {
            for (int register : registers) {
                sb.append("r").append(register).append(", ");
            }
            sb.setLength(sb.length() - 2);
        }
        sb.append("}, ").append(ReferenceUtil.getMethodDescriptor(methodReference));

        return sb.toString();
    }

    @Override
    public int[] getPossibleChildren() {
        return new int[] { childAddress };
    }

    @Override
    public int getAddress() {
        return address;
    }

}