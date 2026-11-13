package mcp.remastered.engine.pipeline.patch.bytecode;

import mcp.remastered.engine.pipeline.patch.bytecode.fixes.*;

import java.util.List;

/**
 * The patches of the bytecode, for the exact game version.
 *
 * <p>They are applied to the jar of the client, before it is decompiled, so that the sources
 * that come out describe the patched game.</p>
 *
 * <p>Only the general ones live here: they recognise a shape in the bytecode and apply
 * everywhere it occurs, so a single patch covers every class that carries the shape. What is
 * specific to one class is not patched here but in the sources, with a difference: the
 * differences of the version live in the folder of the version and are applied to the sources the
 * decompiler writes.</p>
 */
public final class BytecodeFixes {

    private BytecodeFixes() {
    }

    /**
     * The patches of a version.
     *
     * @param version the version the sources belong to
     * @return the patches to apply
     */
    public static List<BytecodePatch> forVersion(String version) {
        return switch (version) {
            case "26.3" -> List.of(
                    // A superclass method that returns its own type needs the conversion back.
                    new GenericSuperReturn(),
                    // The same, for the method of the class itself.
                    new GenericSelfReturn(),
                    // The target of a lambda that the compiler nested in another one.
                    new NestedLambdaTarget(),
                    // The local of a catch block that the compiler reused.
                    new LambdaCatchLocals(),
                    // The helper that takes a Class and returns it.
                    new GenericClassIdentity(),
                    // The two local variables of the same method that share a name.
                    new UniqueLocalNames(),
                    // The method reference that builds a comparator.
                    new ComparatorMethodReference()
            );
            default -> List.of();
        };
    }
}
