package timber.log;

// Compile-only stub mirroring Timber 5 as shipped in F1TV. Never packaged into the patch dex.
public class Timber {
    public static abstract class Tree {
        public Tree() { }
        protected abstract void log(int priority, String tag, String message, Throwable t);
    }

    public static void plant(Tree tree) { throw new UnsupportedOperationException(); }
}
