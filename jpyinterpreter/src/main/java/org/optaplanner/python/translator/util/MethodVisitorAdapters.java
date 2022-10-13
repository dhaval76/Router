package org.optaplanner.python.translator.util;

import java.lang.reflect.Modifier;
import java.util.HashMap;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.CheckMethodAdapter;
import org.optaplanner.python.translator.MethodDescriptor;

public class MethodVisitorAdapters {
    public static MethodVisitor adapt(MethodVisitor methodVisitor, MethodDescriptor method) {
        return adapt(methodVisitor, method.getMethodName(), method.getMethodDescriptor());
    }

    public static MethodVisitor adapt(MethodVisitor methodVisitor, String name, String descriptor) {
        CheckMethodAdapter out = new CheckMethodAdapter(Modifier.PUBLIC, name, descriptor,
                new HandlerSorterAdapter(methodVisitor,
                        Opcodes.ASM9,
                        Modifier.PUBLIC,
                        name,
                        descriptor,
                        null, null),
                new HashMap<>());
        out.version = Opcodes.V11;
        return out;
    }
}
