package mcp.remastered.engine.pipeline.patch;

import java.util.*;

/**
 * The difference between two versions of the game, computed at the class level.
 *
 * <p>A diff is made of three kinds of entries:</p>
 * <ul>
 *   <li>classes that only exist in the new version ({@link Kind#ADDED}),</li>
 *   <li>classes that only existed in the old version ({@link Kind#REMOVED}), and</li>
 *   <li>classes that exist in both but changed ({@link Kind#MODIFIED}).</li>
 * </ul>
 *
 * <p>This is what makes the pipeline able to carry work over from one version to the next: a
 * class that did not change does not need to be touched, so the patches written for it stay
 * valid.</p>
 */
public final class ClassDiff {

    /**
     * The kind of change a diff entry describes.
     */
    public enum Kind {
        ADDED, REMOVED, MODIFIED
    }

    /**
     * A single change between two versions.
     */
    public static final class Entry {
        private final Kind kind;
        private final String className;
        private final List<String> changedMethods;
        private final List<String> addedMethods;
        private final List<String> removedMethods;

        Entry(Kind kind, String className, List<String> changedMethods, List<String> addedMethods, List<String> removedMethods) {
            this.kind = kind;
            this.className = className;
            this.changedMethods = changedMethods;
            this.addedMethods = addedMethods;
            this.removedMethods = removedMethods;
        }

        public Kind kind() {
            return kind;
        }

        /**
         * The internal name of the class, for example {@code net/minecraft/client/Camera}.
         */
        public String className() {
            return className;
        }

        /**
         * The methods that exist in both versions but whose bytecode changed.
         */
        public List<String> changedMethods() {
            return changedMethods;
        }

        /**
         * The methods that only exist in the new version.
         */
        public List<String> addedMethods() {
            return addedMethods;
        }

        /**
         * The methods that were removed in the new version.
         */
        public List<String> removedMethods() {
            return removedMethods;
        }

        @Override
        public String toString() {
            return kind + " " + className;
        }
    }

    private final List<Entry> entries;

    private ClassDiff(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * Compares two class indexes.
     *
     * @param oldIndex the classes of the previous version
     * @param newIndex the classes of the new version
     * @return the difference between the two
     */
    public static ClassDiff between(ClassIndex oldIndex, ClassIndex newIndex) {
        List<Entry> entries = new ArrayList<>();

        Map<String, ClassIndex.ClassInfo> oldClasses = oldIndex.classes();
        Map<String, ClassIndex.ClassInfo> newClasses = newIndex.classes();

        for (Map.Entry<String, ClassIndex.ClassInfo> entry : newClasses.entrySet()) {
            ClassIndex.ClassInfo newClass = entry.getValue();
            ClassIndex.ClassInfo oldClass = oldClasses.get(entry.getKey());

            if (oldClass == null) {
                entries.add(new Entry(Kind.ADDED, entry.getKey(), List.of(), List.of(), List.of()));
                continue;
            }

            if (oldClass.fingerprint().equals(newClass.fingerprint())) {
                continue;
            }

            List<String> changed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();

            for (Map.Entry<String, String> method : newClass.methods().entrySet()) {
                String oldHash = oldClass.methods().get(method.getKey());
                if (oldHash == null) {
                    added.add(method.getKey());
                } else if (!oldHash.equals(method.getValue())) {
                    changed.add(method.getKey());
                }
            }

            Set<String> newMethodKeys = new LinkedHashSet<>(newClass.methods().keySet());
            for (String oldMethod : oldClass.methods().keySet()) {
                if (!newMethodKeys.contains(oldMethod)) {
                    removed.add(oldMethod);
                }
            }

            entries.add(new Entry(Kind.MODIFIED, entry.getKey(), changed, added, removed));
        }

        for (Map.Entry<String, ClassIndex.ClassInfo> entry : oldClasses.entrySet()) {
            if (!newClasses.containsKey(entry.getKey())) {
                entries.add(new Entry(Kind.REMOVED, entry.getKey(), List.of(), List.of(), List.of()));
            }
        }

        entries.sort((a, b) -> a.className().compareTo(b.className()));
        return new ClassDiff(entries);
    }

    /**
     * Every change between the two versions.
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * The number of classes that changed.
     */
    public int size() {
        return entries.size();
    }

    /**
     * The internal names of every class that was added in the new version.
     *
     * @return the added class names
     */
    public List<String> addedClasses() {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.kind() == Kind.ADDED) {
                names.add(entry.className());
            }
        }
        return names;
    }

    /**
     * The internal names of every class that was removed in the new version.
     *
     * @return the removed class names
     */
    public List<String> removedClasses() {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.kind() == Kind.REMOVED) {
                names.add(entry.className());
            }
        }
        return names;
    }

    /**
     * The internal names of every class that changed in the new version.
     *
     * @return the modified class names
     */
    public List<String> modifiedClasses() {
        List<String> names = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.kind() == Kind.MODIFIED) {
                names.add(entry.className());
            }
        }
        return names;
    }
}
