package mcp.remastered.engine.pipeline.verify;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Compares the bytecode of a class with the bytecode of another one.
 *
 * <p>The comparison is about the behaviour, not about the shape of the code: the game is
 * compiled by Mojang and the sources that come out of the decompiler are compiled again by
 * javac, so the two versions of the same method rarely have the same instructions. What has to
 * be the same are the calls, the fields and the constants a method works with: a method that
 * calls something else, or that does not call something at all, does something else.</p>
 */
public final class ClassVerifier {

    /**
     * The differences between two classes.
     */
    public static final class Difference {
        private final String kind;
        private final String detail;

        Difference(String kind, String detail) {
            this.kind = kind;
            this.detail = detail;
        }

        public String kind() {
            return this.kind;
        }

        public String detail() {
            return this.detail;
        }

        @Override
        public String toString() {
            return this.kind + ": " + this.detail;
        }
    }

    private ClassVerifier() {
    }

    /**
     * Compares two classes.
     *
     * @param original   the bytecode the game was built with
     * @param recompiled the bytecode the decompiled sources were compiled into
     * @return the differences, in the order they were found
     */
    public static List<Difference> compare(byte[] original, byte[] recompiled) {
        ClassNode a = read(original);
        ClassNode b = read(recompiled);
        List<Difference> differences = new ArrayList<>();

        if (!Objects.equals(a.signature, b.signature)) {
            differences.add(new Difference("firma di classe diversa", a.name + "  " + a.signature + "  ->  " + b.signature));
        }

        for (FieldNode field : a.fields) {
            FieldNode other = field(b, field.name, field.desc);
            if (other == null) {
                differences.add(new Difference("campo assente", a.name + "  " + field.name + " " + field.desc));
            } else if (!Objects.equals(field.signature, other.signature)) {
                differences.add(new Difference("firma di campo diversa", a.name + "  " + field.name + "  " + field.signature + "  ->  " + other.signature));
            }
        }
        for (FieldNode field : b.fields) {
            if (field(a, field.name, field.desc) == null) {
                differences.add(new Difference("campo in piu", a.name + "  " + field.name + " " + field.desc));
            }
        }

        for (MethodNode method : a.methods) {
            MethodNode other = method(b, method.name, method.desc);
            if (other == null) {
                differences.add(new Difference("metodo assente", a.name + "  " + method.name + method.desc));
                continue;
            }
            if (!Objects.equals(method.signature, other.signature)) {
                differences.add(new Difference("firma di metodo diversa", a.name + "  " + method.name + method.desc + "  " + method.signature + "  ->  " + other.signature));
            }
            compareBodies(a.name, method, other, differences);
        }
        for (MethodNode method : b.methods) {
            if (method(a, method.name, method.desc) == null) {
                differences.add(new Difference("metodo in piu", a.name + "  " + method.name + method.desc));
            }
        }
        return differences;
    }

    private static void compareBodies(String owner, MethodNode original, MethodNode recompiled, List<Difference> differences) {
        List<String> before = effects(original);
        List<String> after = effects(recompiled);
        if (before.equals(after)) {
            return;
        }
        List<String> lost = new ArrayList<>(before);
        List<String> added = new ArrayList<>(after);
        lost.removeAll(after);
        added.removeAll(before);
        for (String call : lost) {
            if (benign(call)) continue;
            differences.add(new Difference("chiamata persa", owner + "  " + original.name + original.desc + "  " + call));
        }
        for (String call : added) {
            if (benign(call)) continue;
            differences.add(new Difference("chiamata nuova", owner + "  " + original.name + original.desc + "  " + call));
        }
    }

    /**
     * Whether a call is one the compiler adds and removes on its own.
     *
     * <p>The synthetic accessors of the old compilers and the null checks javac writes when a
     * receiver is generic are noise: they do not change what the method does.</p>
     *
     * @param call the call
     * @return true when the call does not change the behaviour
     */
    private static boolean benign(String call) {
        return call.contains("Objects.requireNonNull") || call.contains("access$");
    }

    private static FieldNode field(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) return field;
        }
        return null;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG);
        return node;
    }

    /**
     * What a method works with: the calls it makes, the fields it touches, the constants it uses.
     *
     * @param method the method
     * @return the effects, sorted, so that two methods can be compared
     */
    public static List<String> effects(MethodNode method) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) {
                out.add("chiama " + call.owner + "." + call.name + call.desc);
            } else if (insn instanceof FieldInsnNode field) {
                out.add("campo " + field.owner + "." + field.name + " " + field.desc);
            } else if (insn instanceof LdcInsnNode ldc) {
                out.add("ldc " + value(ldc.cst));
            } else if (insn instanceof InvokeDynamicInsnNode indy) {
                out.add("indy " + indy.name + indy.desc + " " + bootstrap(indy));
            } else if (insn instanceof TypeInsnNode type) {
                out.add("tipo " + type.desc);
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * The call site of a lambda: two lambdas that end up calling the same method are the same
     * thing, however the compiler wrote them down.
     */
    private static String bootstrap(InvokeDynamicInsnNode indy) {
        Handle handle = indy.bsm;
        if (handle == null) return "?";
        if (handle.getOwner().equals("java/lang/invoke/LambdaMetafactory") && indy.bsmArgs.length >= 2
                && indy.bsmArgs[1] instanceof Handle sam) {
            return "metafactory su " + sam.getOwner() + "." + sam.getName() + sam.getDesc();
        }
        return handle.getOwner() + "." + handle.getName() + handle.getDesc();
    }

    private static String value(Object constant) {
        if (constant instanceof String s) return "\"" + s + "\"";
        if (constant instanceof Type t) return t.getDescriptor();
        if (constant instanceof Handle h) return h.getOwner() + "." + h.getName();
        if (constant instanceof ConstantDynamic) return "costante dinamica";
        return String.valueOf(constant);
    }
}
