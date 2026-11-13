package mcp.remastered.engine.pipeline.patch.bytecode.fixes;

import mcp.remastered.engine.pipeline.patch.bytecode.BytecodePatch;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.*;

/**
 * Gives every local variable of a method a name of its own.
 *
 * <p>The debug names of two different variables can be the same, for instance when the
 * compiler reuses a name in two branches of a switch. The decompiler then emits both
 * declarations with that name, and Java refuses to compile a local variable that shadows
 * another one. Renaming the debug names leaves the code untouched and lets the decompiler
 * produce a source that compiles.</p>
 */
public final class UniqueLocalNames implements BytecodePatch {
    @Override
    public boolean apply(ClassNode clazz) {
        boolean changed = false;
        for (MethodNode method : clazz.methods) {
            if (method.localVariables == null || method.localVariables.size() < 2) continue;
            changed |= rename(method);
        }
        return changed;
    }

    /**
     * Renames the variables of a method that share a name with an earlier, different one.
     */
    private static boolean rename(MethodNode method) {
        Map<AbstractInsnNode, Integer> positions = positions(method);
        List<LocalVariableNode> ordered = new ArrayList<>(method.localVariables);
        ordered.sort(Comparator.comparingInt(local -> position(positions, local.start)));

        Map<String, List<LocalVariableNode>> byName = new LinkedHashMap<>();
        for (LocalVariableNode local : ordered) {
            byName.computeIfAbsent(local.name, key -> new ArrayList<>()).add(local);
        }

        boolean changed = false;
        for (Map.Entry<String, List<LocalVariableNode>> entry : byName.entrySet()) {
            List<LocalVariableNode> group = entry.getValue();
            if (group.size() < 2) continue;

            List<LocalVariableNode> accepted = new ArrayList<>();
            int counter = 0;
            for (LocalVariableNode local : group) {
                boolean same = false;
                for (LocalVariableNode other : accepted) {
                    if (isSameVariable(method, positions, other, local)) {
                        same = true;
                        break;
                    }
                }
                if (same) continue;
                if (accepted.isEmpty()) {
                    accepted.add(local);
                    continue;
                }
                accepted.add(local);
                String candidate;
                do {
                    counter++;
                    candidate = entry.getKey() + counter;
                } while (taken(ordered, candidate));
                local.name = candidate;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Maps every instruction of a method to its position.
     */
    private static Map<AbstractInsnNode, Integer> positions(MethodNode method) {
        Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            positions.put(instruction, index++);
        }
        return positions;
    }

    private static int position(Map<AbstractInsnNode, Integer> positions, AbstractInsnNode instruction) {
        Integer position = positions.get(instruction);
        return position == null ? Integer.MAX_VALUE : position;
    }

    /**
     * Tells whether two entries describe the same variable.
     */
    private static boolean isSameVariable(MethodNode method, Map<AbstractInsnNode, Integer> positions, LocalVariableNode first, LocalVariableNode second) {
        if (first.index != second.index || !first.desc.equals(second.desc)) return false;
        int firstStart = position(positions, first.start);
        int firstEnd = position(positions, first.end);
        int secondStart = position(positions, second.start);
        int secondEnd = position(positions, second.end);
        return firstStart < secondEnd && secondStart < firstEnd;
    }

    /**
     * Tells whether a name is already taken by another variable.
     */
    private static boolean taken(List<LocalVariableNode> variables, String name) {
        for (LocalVariableNode local : variables) {
            if (local.name.equals(name)) return true;
        }
        return false;
    }
}
