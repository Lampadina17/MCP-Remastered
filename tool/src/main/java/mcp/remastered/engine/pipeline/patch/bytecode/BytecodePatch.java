package mcp.remastered.engine.pipeline.patch.bytecode;

import org.objectweb.asm.tree.ClassNode;

/**
 * A change applied to the bytecode of the game, before it is decompiled.
 *
 * <p>The patches work on the classes the way they are: they are the ones that make the game
 * behave the way the project wants it to, and the decompiler sees the result, so the sources
 * that come out describe the patched game and not the original one.</p>
 */
public interface BytecodePatch {

    /**
     * The name of the class the patch applies to.
     *
     * @return the name of the class, in the form of the JVM, or {@code null} when the patch
     * applies to every class
     */
    default String className() {
        return null;
    }

    /**
     * The change itself.
     *
     * @param clazz the class to change, already read and ready to be written back
     * @return whether the class was changed
     */
    boolean apply(ClassNode clazz);
}
