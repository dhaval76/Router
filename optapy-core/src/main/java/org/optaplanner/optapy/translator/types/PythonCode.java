package org.optaplanner.optapy.translator.types;

import org.optaplanner.optapy.PythonLikeObject;

/**
 * Holds a reference to a PythonLikeFunction's Class. Cannot use {@link Class},
 * since code can be accessed like a {@code PythonLikeObject}
 */
public class PythonCode extends AbstractPythonLikeObject {
    public static final PythonLikeType CODE_TYPE = new PythonLikeType("code");

    /**
     * The class of the function that implement the code
     */
    public final Class<? extends PythonLikeFunction> functionClass;

    public PythonCode(final Class<? extends PythonLikeFunction> functionClass) {
        super(CODE_TYPE);
        this.functionClass = functionClass;
    }
}
