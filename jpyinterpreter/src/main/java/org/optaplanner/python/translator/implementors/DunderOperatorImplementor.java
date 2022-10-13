package org.optaplanner.python.translator.implementors;

import static org.optaplanner.python.translator.types.BuiltinTypes.BASE_TYPE;
import static org.optaplanner.python.translator.types.BuiltinTypes.NOT_IMPLEMENTED_TYPE;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.optaplanner.python.translator.CompareOp;
import org.optaplanner.python.translator.LocalVariableHelper;
import org.optaplanner.python.translator.MethodDescriptor;
import org.optaplanner.python.translator.PythonBinaryOperators;
import org.optaplanner.python.translator.PythonFunctionSignature;
import org.optaplanner.python.translator.PythonLikeObject;
import org.optaplanner.python.translator.PythonTernaryOperators;
import org.optaplanner.python.translator.PythonUnaryOperator;
import org.optaplanner.python.translator.StackMetadata;
import org.optaplanner.python.translator.types.BuiltinTypes;
import org.optaplanner.python.translator.types.NotImplemented;
import org.optaplanner.python.translator.types.PythonKnownFunctionType;
import org.optaplanner.python.translator.types.PythonLikeFunction;
import org.optaplanner.python.translator.types.PythonLikeType;
import org.optaplanner.python.translator.types.collections.PythonLikeList;
import org.optaplanner.python.translator.types.errors.TypeError;

/**
 * Implementations of opcodes that delegate to dunder/magic methods.
 */
public class DunderOperatorImplementor {

    public static void unaryOperator(MethodVisitor methodVisitor, StackMetadata stackMetadata, PythonUnaryOperator operator) {
        PythonLikeType operand = Optional.ofNullable(stackMetadata.getTOSType()).orElse(BASE_TYPE);

        Optional<PythonKnownFunctionType> maybeKnownFunctionType = operand.getMethodType(operator.getDunderMethod());
        if (maybeKnownFunctionType.isPresent()) {
            PythonKnownFunctionType knownFunctionType = maybeKnownFunctionType.get();
            Optional<PythonFunctionSignature> maybeFunctionSignature = knownFunctionType.getFunctionForParameters();
            if (maybeFunctionSignature.isPresent()) {
                PythonFunctionSignature functionSignature = maybeFunctionSignature.get();
                MethodDescriptor methodDescriptor = functionSignature.getMethodDescriptor();
                if (methodDescriptor.getParameterTypes().length < 1) {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, methodDescriptor.getDeclaringClassInternalName());
                } else {
                    methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, methodDescriptor.getParameterTypes()[0].getInternalName());
                }
                functionSignature.getMethodDescriptor().callMethod(methodVisitor);
            } else {
                unaryOperator(methodVisitor, operator);
            }
        } else {
            unaryOperator(methodVisitor, operator);
        }
    }

    /**
     * Performs a unary dunder operation on TOS. Generate codes that look like this:
     *
     * <code>
     * <pre>
     *    BiFunction[List, Map, Result] operand_method = TOS.__getType().__getAttributeOrError(operator.getDunderMethod());
     *    List args = new ArrayList(1);
     *    args.set(0) = TOS
     *    pop TOS
     *    TOS' = operand_method.apply(args, null)
     * </pre>
     * </code>
     *
     */
    public static void unaryOperator(MethodVisitor methodVisitor, PythonUnaryOperator operator) {
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                true);
        methodVisitor.visitLdcInsn(operator.getDunderMethod());
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getAttributeOrError", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(String.class)),
                true);

        // Stack is now TOS, method
        methodVisitor.visitInsn(Opcodes.DUP_X1);
        methodVisitor.visitInsn(Opcodes.POP);

        // Stack is now method, TOS
        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(PythonLikeList.class));
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(PythonLikeList.class), "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE), false);

        // Stack is now method, TOS, argList
        pushArgumentIntoList(methodVisitor);

        // Stack is now method, argList
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeFunction.class),
                "__call__", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(List.class),
                        Type.getType(Map.class)),
                true);
    }

    public static void binaryOperator(MethodVisitor methodVisitor, StackMetadata stackMetadata,
            PythonBinaryOperators operator) {
        binaryOperator(methodVisitor, stackMetadata, operator, true, true);
    }

    private static void binaryOperator(MethodVisitor methodVisitor, StackMetadata stackMetadata,
            PythonBinaryOperators operator, boolean isLeft, boolean leftCheckSuccessful) {
        PythonLikeType leftOperand =
                Optional.ofNullable(stackMetadata.getTypeAtStackIndex(1)).orElse(BASE_TYPE);
        PythonLikeType rightOperand =
                Optional.ofNullable(stackMetadata.getTypeAtStackIndex(0)).orElse(BASE_TYPE);

        Optional<PythonKnownFunctionType> maybeKnownFunctionType =
                isLeft ? leftOperand.getMethodType(operator.getDunderMethod())
                        : rightOperand.getMethodType(operator.getRightDunderMethod());
        if (maybeKnownFunctionType.isPresent()) {
            PythonKnownFunctionType knownFunctionType = maybeKnownFunctionType.get();
            Optional<PythonFunctionSignature> maybeFunctionSignature =
                    isLeft ? knownFunctionType.getFunctionForParameters(rightOperand)
                            : knownFunctionType.getFunctionForParameters(leftOperand);
            if (maybeFunctionSignature.isPresent()) {
                PythonFunctionSignature functionSignature = maybeFunctionSignature.get();
                MethodDescriptor methodDescriptor = functionSignature.getMethodDescriptor();
                boolean needToCheckForNotImplemented = operator.hasRightDunderMethod() &&
                        NOT_IMPLEMENTED_TYPE.isSubclassOf(functionSignature.getReturnType());

                if (isLeft) {
                    if (methodDescriptor.getParameterTypes().length < 2) {
                        methodVisitor.visitInsn(Opcodes.SWAP);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST, methodDescriptor.getDeclaringClassInternalName());
                        methodVisitor.visitInsn(Opcodes.SWAP);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                                methodDescriptor.getParameterTypes()[0].getInternalName());
                    } else {
                        methodVisitor.visitInsn(Opcodes.SWAP);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                                methodDescriptor.getParameterTypes()[0].getInternalName());
                        methodVisitor.visitInsn(Opcodes.SWAP);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                                methodDescriptor.getParameterTypes()[1].getInternalName());
                    }
                } else {
                    if (methodDescriptor.getParameterTypes().length < 2) {
                        methodVisitor.visitInsn(Opcodes.SWAP);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                                methodDescriptor.getParameterTypes()[0].getInternalName());
                        methodVisitor.visitInsn(Opcodes.SWAP);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                                methodDescriptor.getDeclaringClassInternalName());
                    } else {
                        methodVisitor.visitInsn(Opcodes.SWAP);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                                methodDescriptor.getParameterTypes()[1].getInternalName());
                        methodVisitor.visitInsn(Opcodes.SWAP);
                        methodVisitor.visitTypeInsn(Opcodes.CHECKCAST,
                                methodDescriptor.getParameterTypes()[0].getInternalName());
                    }
                }

                if (needToCheckForNotImplemented) {
                    methodVisitor.visitInsn(Opcodes.DUP2);
                }

                if (!isLeft) {
                    methodVisitor.visitInsn(Opcodes.SWAP);
                }
                functionSignature.getMethodDescriptor().callMethod(methodVisitor);
                if (needToCheckForNotImplemented) {
                    methodVisitor.visitInsn(Opcodes.DUP);
                    methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(NotImplemented.class),
                            "INSTANCE", Type.getDescriptor(NotImplemented.class));
                    Label ifNotImplemented = new Label();
                    Label done = new Label();

                    methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, ifNotImplemented);

                    methodVisitor.visitInsn(Opcodes.DUP_X2);
                    methodVisitor.visitInsn(Opcodes.POP);
                    methodVisitor.visitInsn(Opcodes.POP2);
                    methodVisitor.visitJumpInsn(Opcodes.GOTO, done);

                    methodVisitor.visitLabel(ifNotImplemented);
                    if (isLeft) {
                        methodVisitor.visitInsn(Opcodes.POP);
                        binaryOperator(methodVisitor, stackMetadata, operator, false, true);
                    } else {
                        methodVisitor.visitInsn(Opcodes.POP);
                        raiseUnsupportedType(methodVisitor, stackMetadata.localVariableHelper, operator);
                    }
                    methodVisitor.visitLabel(done);
                }
            } else if (isLeft && operator.hasRightDunderMethod()) {
                binaryOperator(methodVisitor, stackMetadata, operator, false, false);
            } else if (!isLeft && leftCheckSuccessful) {
                binaryOperatorOnlyRight(methodVisitor, stackMetadata.localVariableHelper, operator);
            } else {
                binaryOperator(methodVisitor, stackMetadata.localVariableHelper, operator);
            }
        } else if (isLeft && operator.hasRightDunderMethod()) {
            binaryOperator(methodVisitor, stackMetadata, operator, false, false);
        } else if (!isLeft && leftCheckSuccessful) {
            binaryOperatorOnlyRight(methodVisitor, stackMetadata.localVariableHelper, operator);
        } else {
            binaryOperator(methodVisitor, stackMetadata.localVariableHelper, operator);
        }
    }

    private static void raiseUnsupportedType(MethodVisitor methodVisitor, LocalVariableHelper localVariableHelper,
            PythonBinaryOperators operator) {
        int right = localVariableHelper.newLocal();
        int left = localVariableHelper.newLocal();

        localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeObject.class), left);
        localVariableHelper.writeTemp(methodVisitor, Type.getType(PythonLikeObject.class), right);

        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(TypeError.class));
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(StringBuilder.class));
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(StringBuilder.class),
                "<init>", Type.getMethodDescriptor(Type.VOID_TYPE), false);
        if (!operator.getOperatorSymbol().isEmpty()) {
            methodVisitor.visitLdcInsn("unsupported operand type(s) for " + operator.getOperatorSymbol() + ": '");
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(StringBuilder.class),
                    "append", Type.getMethodDescriptor(Type.getType(StringBuilder.class),
                            Type.getType(String.class)),
                    false);
            localVariableHelper.readTemp(methodVisitor, Type.getType(PythonLikeObject.class), left);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                    "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                    true);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(PythonLikeType.class),
                    "getTypeName", Type.getMethodDescriptor(Type.getType(String.class)),
                    false);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(StringBuilder.class),
                    "append", Type.getMethodDescriptor(Type.getType(StringBuilder.class),
                            Type.getType(String.class)),
                    false);
            methodVisitor.visitLdcInsn("' and '");
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(StringBuilder.class),
                    "append", Type.getMethodDescriptor(Type.getType(StringBuilder.class),
                            Type.getType(String.class)),
                    false);
            localVariableHelper.readTemp(methodVisitor, Type.getType(PythonLikeObject.class), right);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                    "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                    true);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(PythonLikeType.class),
                    "getTypeName", Type.getMethodDescriptor(Type.getType(String.class)),
                    false);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(StringBuilder.class),
                    "append", Type.getMethodDescriptor(Type.getType(StringBuilder.class),
                            Type.getType(String.class)),
                    false);
            methodVisitor.visitLdcInsn("'");
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(StringBuilder.class),
                    "append", Type.getMethodDescriptor(Type.getType(StringBuilder.class),
                            Type.getType(String.class)),
                    false);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(StringBuilder.class),
                    "toString", Type.getMethodDescriptor(Type.getType(String.class)),
                    false);

            localVariableHelper.freeLocal();
            localVariableHelper.freeLocal();

            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(TypeError.class),
                    "<init>", Type.getMethodDescriptor(Type.VOID_TYPE,
                            Type.getType(String.class)),
                    false);
            methodVisitor.visitInsn(Opcodes.ATHROW);
        } else {
            localVariableHelper.freeLocal();
            localVariableHelper.freeLocal();

            switch (operator) {
                case GET_ITEM: // TODO: Error message
                default:
                    methodVisitor.visitInsn(Opcodes.POP);
                    methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(TypeError.class),
                            "<init>", Type.getMethodDescriptor(Type.VOID_TYPE),
                            false);
                    methodVisitor.visitInsn(Opcodes.ATHROW);
            }
        }
    }

    /**
     * Performs a binary dunder operation on TOS and TOS1. Generate codes that look like this:
     *
     * <code>
     * <pre>
     *    BiFunction[List, Map, Result] operand_method = TOS1.__getType().__getAttributeOrError(operator.getDunderMethod());
     *    List args = new ArrayList(2);
     *    args.set(0) = TOS1
     *    args.set(1) = TOS
     *    pop TOS, TOS1
     *    TOS' = operand_method.apply(args, null)
     * </pre>
     * </code>
     *
     */
    public static void binaryOperator(MethodVisitor methodVisitor, LocalVariableHelper localVariableHelper,
            PythonBinaryOperators operator) {
        Label noLeftMethod = new Label();
        methodVisitor.visitInsn(Opcodes.DUP2);
        if (operator.hasRightDunderMethod()) {
            methodVisitor.visitInsn(Opcodes.DUP2);
        }
        methodVisitor.visitInsn(Opcodes.SWAP);

        // Stack is now (TOS1, TOS,)? TOS, TOS1
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                true);
        methodVisitor.visitLdcInsn(operator.getDunderMethod());
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getAttributeOrNull", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(String.class)),
                true);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, noLeftMethod);

        // Stack is now(TOS1, TOS,)? TOS, TOS1, method
        methodVisitor.visitInsn(Opcodes.DUP_X2);
        methodVisitor.visitInsn(Opcodes.POP);

        // Stack is now (TOS1, TOS,)? method, TOS, TOS1
        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(PythonLikeList.class));
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(PythonLikeList.class), "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE), false);

        // Stack is now (TOS1, TOS,)? method, TOS, TOS1, argList
        pushArgumentIntoList(methodVisitor);
        pushArgumentIntoList(methodVisitor);

        // Stack is now (TOS1, TOS,)? method, argList
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeFunction.class),
                "__call__", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(List.class),
                        Type.getType(Map.class)),
                true);

        // Stack is now (TOS1, TOS,)? method_result
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(NotImplemented.class),
                "INSTANCE", Type.getDescriptor(NotImplemented.class));
        Label ifNotImplemented = new Label();
        Label done = new Label();

        methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, ifNotImplemented);
        // Stack is TOS1, TOS, method_result
        if (operator.hasRightDunderMethod()) {
            methodVisitor.visitInsn(Opcodes.DUP_X2);
            methodVisitor.visitInsn(Opcodes.POP);
            methodVisitor.visitInsn(Opcodes.POP2);
        }
        // Stack is method_result
        methodVisitor.visitJumpInsn(Opcodes.GOTO, done);

        methodVisitor.visitLabel(noLeftMethod);
        methodVisitor.visitInsn(Opcodes.POP2);
        methodVisitor.visitLabel(ifNotImplemented);
        methodVisitor.visitInsn(Opcodes.POP);

        Label raiseError = new Label();
        if (operator.hasRightDunderMethod()) {
            Label noRightMethod = new Label();
            // Stack is now TOS1, TOS
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                    "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                    true);
            methodVisitor.visitLdcInsn(operator.getRightDunderMethod());
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                    "__getAttributeOrNull", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                            Type.getType(String.class)),
                    true);
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitInsn(Opcodes.ACONST_NULL);
            methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, noRightMethod);

            // Stack is now TOS1, TOS, method
            methodVisitor.visitInsn(Opcodes.DUP_X2);
            methodVisitor.visitInsn(Opcodes.POP);

            // Stack is now method, TOS1, TOS
            methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(PythonLikeList.class));
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(PythonLikeList.class), "<init>",
                    Type.getMethodDescriptor(Type.VOID_TYPE), false);

            // Stack is now method, TOS1, TOS, argList
            pushArgumentIntoList(methodVisitor);
            pushArgumentIntoList(methodVisitor);

            // Stack is now method, argList
            methodVisitor.visitInsn(Opcodes.ACONST_NULL);
            methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeFunction.class),
                    "__call__", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                            Type.getType(List.class),
                            Type.getType(Map.class)),
                    true);

            // Stack is now method_result
            methodVisitor.visitInsn(Opcodes.DUP);
            methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(NotImplemented.class),
                    "INSTANCE", Type.getDescriptor(NotImplemented.class));

            methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, done);
            // Stack is TOS1, TOS, NotImplemented
            methodVisitor.visitInsn(Opcodes.POP);
            methodVisitor.visitJumpInsn(Opcodes.GOTO, raiseError);

            methodVisitor.visitLabel(noRightMethod);
            methodVisitor.visitInsn(Opcodes.POP);
            methodVisitor.visitInsn(Opcodes.POP2);
        }
        methodVisitor.visitLabel(raiseError);
        methodVisitor.visitInsn(Opcodes.SWAP);
        raiseUnsupportedType(methodVisitor, localVariableHelper, operator);

        methodVisitor.visitLabel(done);
        methodVisitor.visitInsn(Opcodes.DUP_X2);
        methodVisitor.visitInsn(Opcodes.POP);
        methodVisitor.visitInsn(Opcodes.POP2);

    }

    public static void binaryOperatorOnlyRight(MethodVisitor methodVisitor, LocalVariableHelper localVariableHelper,
            PythonBinaryOperators operator) {
        Label done = new Label();
        Label raiseError = new Label();
        Label noRightMethod = new Label();

        methodVisitor.visitInsn(Opcodes.DUP2);

        // Stack is now TOS1, TOS
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                true);
        methodVisitor.visitLdcInsn(operator.getRightDunderMethod());
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getAttributeOrNull", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(String.class)),
                true);
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitJumpInsn(Opcodes.IF_ACMPEQ, noRightMethod);

        // Stack is now TOS1, TOS, method
        methodVisitor.visitInsn(Opcodes.DUP_X2);
        methodVisitor.visitInsn(Opcodes.POP);

        // Stack is now method, TOS1, TOS
        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(PythonLikeList.class));
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(PythonLikeList.class), "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE), false);

        // Stack is now method, TOS1, TOS, argList
        pushArgumentIntoList(methodVisitor);
        pushArgumentIntoList(methodVisitor);

        // Stack is now method, argList
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeFunction.class),
                "__call__", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(List.class),
                        Type.getType(Map.class)),
                true);

        // Stack is now method_result
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(NotImplemented.class),
                "INSTANCE", Type.getDescriptor(NotImplemented.class));

        methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, done);
        // Stack is TOS1, TOS, NotImplemented
        methodVisitor.visitInsn(Opcodes.POP);
        methodVisitor.visitJumpInsn(Opcodes.GOTO, raiseError);

        methodVisitor.visitLabel(noRightMethod);
        methodVisitor.visitInsn(Opcodes.POP);
        methodVisitor.visitInsn(Opcodes.POP2);

        methodVisitor.visitLabel(raiseError);
        methodVisitor.visitInsn(Opcodes.SWAP);
        raiseUnsupportedType(methodVisitor, localVariableHelper, operator);

        methodVisitor.visitLabel(done);
        methodVisitor.visitInsn(Opcodes.DUP_X2);
        methodVisitor.visitInsn(Opcodes.POP);
        methodVisitor.visitInsn(Opcodes.POP2);
    }

    /**
     * Performs a ternary dunder operation on TOS, TOS1 and TOS2. Generate codes that look like this:
     *
     * <code>
     * <pre>
     *    BiFunction[List, Map, Result] operand_method = TOS2.__getType().__getAttributeOrError(operator.getDunderMethod());
     *    List args = new ArrayList(2);
     *    args.set(0) = TOS2
     *    args.set(1) = TOS1
     *    args.set(2) = TOS
     *    pop TOS, TOS1, TOS2
     *    TOS' = operand_method.apply(args, null)
     * </pre>
     * </code>
     *
     */
    public static void ternaryOperator(MethodVisitor methodVisitor, PythonTernaryOperators operator,
            LocalVariableHelper localVariableHelper) {
        StackManipulationImplementor.rotateThree(methodVisitor);
        methodVisitor.visitInsn(Opcodes.SWAP);
        // Stack is now TOS, TOS1, TOS2
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                true);
        methodVisitor.visitLdcInsn(operator.getDunderMethod());
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getAttributeOrError", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(String.class)),
                true);
        // Stack is now TOS, TOS1, TOS2, method
        StackManipulationImplementor.rotateFour(methodVisitor, localVariableHelper);

        // Stack is now method, TOS, TOS1, TOS2
        methodVisitor.visitTypeInsn(Opcodes.NEW, Type.getInternalName(PythonLikeList.class));
        methodVisitor.visitInsn(Opcodes.DUP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(PythonLikeList.class), "<init>",
                Type.getMethodDescriptor(Type.VOID_TYPE), false);

        // Stack is now method, TOS, TOS1, TOS2, argList
        pushArgumentIntoList(methodVisitor);
        pushArgumentIntoList(methodVisitor);
        pushArgumentIntoList(methodVisitor);

        // Stack is now method, argList
        methodVisitor.visitInsn(Opcodes.ACONST_NULL);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeFunction.class),
                "__call__", Type.getMethodDescriptor(Type.getType(PythonLikeObject.class),
                        Type.getType(List.class),
                        Type.getType(Map.class)),
                true);
    }

    /**
     * TOS is a list and TOS1 is an argument. Pushes TOS1 into TOS, and leave TOS on the stack (pops TOS1).
     */
    private static void pushArgumentIntoList(MethodVisitor methodVisitor) {
        methodVisitor.visitInsn(Opcodes.DUP_X1);
        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(List.class),
                "add",
                Type.getMethodDescriptor(Type.BOOLEAN_TYPE, Type.getType(Object.class)),
                true);
        methodVisitor.visitInsn(Opcodes.POP);
    }

    /**
     * Compares TOS and TOS1 via their dunder methods. {@code CompareOp} indicates the operation
     * to perform.
     */
    public static void compareValues(MethodVisitor methodVisitor, StackMetadata stackMetadata, CompareOp op) {
        switch (op) {
            case LESS_THAN:
                binaryOperator(methodVisitor, stackMetadata, PythonBinaryOperators.LESS_THAN);
                break;
            case LESS_THAN_OR_EQUALS:
                binaryOperator(methodVisitor, stackMetadata, PythonBinaryOperators.LESS_THAN_OR_EQUAL);
                break;
            case EQUALS:
            case NOT_EQUALS:
                binaryOpOverridingLeftIfSpecific(methodVisitor, stackMetadata, op);
                break;
            case GREATER_THAN:
                binaryOperator(methodVisitor, stackMetadata, PythonBinaryOperators.GREATER_THAN);
                break;
            case GREATER_THAN_OR_EQUALS:
                binaryOperator(methodVisitor, stackMetadata, PythonBinaryOperators.GREATER_THAN_OR_EQUAL);
                break;
            default:
                throw new IllegalStateException("Unhandled branch: " + op);
        }
    }

    private static void binaryOpOverridingLeftIfSpecific(MethodVisitor methodVisitor, StackMetadata stackMetadata,
            CompareOp op) {
        switch (op) {
            case EQUALS:
            case NOT_EQUALS:
                break;
            default:
                throw new IllegalArgumentException("Should only be called for equals and not equals");
        }

        PythonBinaryOperators operator =
                (op == CompareOp.EQUALS) ? PythonBinaryOperators.EQUAL : PythonBinaryOperators.NOT_EQUAL;

        // If we know TOS1 defines == or !=, we don't need to go here
        if (stackMetadata.getTypeAtStackIndex(1).getDefiningTypeOrNull(operator.getDunderMethod()) != BASE_TYPE) {
            binaryOperator(methodVisitor, stackMetadata, operator);
            return;
        }

        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitInsn(Opcodes.DUP_X1);

        methodVisitor.visitMethodInsn(Opcodes.INVOKEINTERFACE, Type.getInternalName(PythonLikeObject.class),
                "__getType", Type.getMethodDescriptor(Type.getType(PythonLikeType.class)),
                true);
        methodVisitor.visitLdcInsn(operator.getDunderMethod());
        methodVisitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Type.getInternalName(PythonLikeType.class),
                "getDefiningTypeOrNull", Type.getMethodDescriptor(Type.getType(PythonLikeType.class),
                        Type.getType(String.class)),
                false);
        methodVisitor.visitFieldInsn(Opcodes.GETSTATIC, Type.getInternalName(BuiltinTypes.class),
                "BASE_TYPE", Type.getDescriptor(PythonLikeType.class));

        Label ifDefined = new Label();
        methodVisitor.visitJumpInsn(Opcodes.IF_ACMPNE, ifDefined);
        methodVisitor.visitInsn(Opcodes.SWAP);
        methodVisitor.visitLabel(ifDefined);
        binaryOperator(methodVisitor, stackMetadata.localVariableHelper, operator);
    }
}
