package mcp.remastered.engine.pipeline.patch.bytecode.fixes;

import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatch;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes a generic identity helper usable in an inference position.
 *
 * <p>A helper that takes a {@code Class<? super T>} and returns a {@code Class<T>} cannot be
 * used where a {@code Class<? extends A>} is expected: the compiler captures the wildcard and
 * refuses to pick a type for {@code T}. Dropping the wildcard turns the parameter into a plain
 * {@code Class<T>}, which lets the call site infer the type it needs. The erased descriptor and
 * the code of the method are left untouched.</p>
 */
public final class GenericClassIdentity implements BytecodePatch {

    private static final String DESCRIPTOR = "(Ljava/lang/Class;)Ljava/lang/Class;";

    @Override
    public boolean apply(ClassNode clazz) {
        boolean changed = false;
        for (MethodNode method : clazz.methods) {
            if (method.signature == null || !method.desc.equals(DESCRIPTOR) || !returnsItsArgument(method)) continue;
            String fixed = dropWildcard(method.signature);
            if (fixed == null) continue;
            method.signature = fixed;
            changed = true;
        }
        return changed;
    }

    /**
     * Tells whether the method only returns its argument.
     */
    private static boolean returnsItsArgument(MethodNode method) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != Opcodes.ACC_STATIC)
            return false;
        AbstractInsnNode first = method.instructions.getFirst();
        while (first != null && first.getOpcode() < 0) first = first.getNext();
        AbstractInsnNode second = first == null ? null : first.getNext();
        while (second != null && second.getOpcode() < 0) second = second.getNext();
        AbstractInsnNode third = second == null ? null : second.getNext();
        while (third != null && third.getOpcode() < 0) third = third.getNext();
        if (!(first instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return false;
        if (second == null || second.getOpcode() != Opcodes.ARETURN) return false;
        if (third != null) {
            return third.getOpcode() == Opcodes.CHECKCAST && third instanceof TypeInsnNode cast && cast.desc.equals("java/lang/Class");
        }
        return true;
    }

    /**
     * Removes the wildcard of the parameter of a {@code Class<? super T>}.
     *
     * @param signature the generic signature of the method
     * @return the new signature, or {@code null} when the signature does not match
     */
    private static String dropWildcard(String signature) {
        int open = signature.indexOf('(');
        int close = signature.indexOf(')', open);
        if (open < 0 || close < 0) return null;
        String parameter = signature.substring(open + 1, close);
        int start = parameter.indexOf('<');
        int end = parameter.indexOf('>', start);
        if (start < 0 || end < 0) return null;
        String argument = parameter.substring(start + 1, end);
        if (!argument.startsWith("-")) return null;

        String candidate = signature.substring(0, open + 1) + parameter.substring(0, start + 1) + argument.substring(1) + parameter.substring(end) + signature.substring(close);
        return describesPlainVariable(candidate) ? candidate : null;
    }

    /**
     * Checks that the signature declares a parameter that is a plain type variable.
     */
    private static boolean describesPlainVariable(String signature) {
        List<String> formals = new ArrayList<>();
        boolean[] plain = {false};
        boolean[] seen = {false};
        new SignatureReader(signature).accept(new SignatureVisitor(Opcodes.ASM9) {
            @Override
            public void visitFormalTypeParameter(String name) {
                formals.add(name);
            }

            @Override
            public SignatureVisitor visitParameterType() {
                seen[0] = true;
                return new SignatureVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitClassType(String owner) {
                        if (!owner.equals("java/lang/Class")) plain[0] = false;
                    }

                    @Override
                    public SignatureVisitor visitTypeArgument(char wildcard) {
                        if (wildcard != '=') plain[0] = false;
                        return new SignatureVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitTypeVariable(String variable) {
                                if (wildcard == '=' && formals.contains(variable)) plain[0] = true;
                            }
                        };
                    }
                };
            }
        });
        return seen[0] && plain[0];
    }
}
