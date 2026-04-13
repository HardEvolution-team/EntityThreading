package ded.entitythreading.schedule;

import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * An {@link ArrayList} subclass that defers mutations from worker threads
 * and serves reads from a snapshot to prevent concurrent modification.
 * <p>
 * FIX: Replaced CopyOnWriteArrayList snapshot with immutable array snapshot
 * to fix broken double-checked locking (clear+add on COWAL is not atomic).
 * <p>
 * FIX: Snapshot rebuild now creates a new list atomically instead of
 * mutating a shared CopyOnWriteArrayList.
 */
public class DeferredArrayList<E> extends ArrayList<E> {

    /**
     * Immutable snapshot for worker thread reads.
     * Volatile ensures visibility; the reference is replaced atomically.
     */
    private volatile List<E> snapshot = List.of();
    private volatile boolean snapshotDirty = true;

    public DeferredArrayList(Collection<? extends E> c) {
        super(c);
        rebuildSnapshot();
    }

    private static boolean isWorker() {
        return EntityWorkerThread.isCurrentThreadWorker();
    }

    private void markDirty() {
        snapshotDirty = true;
    }

    @SuppressWarnings("unchecked")
    private void rebuildSnapshot() {
        if (snapshotDirty) {
            synchronized (this) {
                if (snapshotDirty) {
                    // Create a new immutable list atomically
                    Object[] raw = super.toArray();
                    List<E> newSnapshot = new ArrayList<>(raw.length);
                    for (Object o : raw) {
                        newSnapshot.add((E) o);
                    }
                    this.snapshot = Collections.unmodifiableList(newSnapshot);
                    snapshotDirty = false;
                }
            }
        }
    }

    private List<E> safeSnapshot() {
        rebuildSnapshot();
        return snapshot;
    }

    // --- Mutating operations: defer from workers ---

    @Override
    public boolean add(E e) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> { super.add(e); markDirty(); });
            return true;
        }
        boolean r = super.add(e);
        markDirty();
        return r;
    }

    @Override
    public void add(int index, E element) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> { super.add(index, element); markDirty(); });
            return;
        }
        super.add(index, element);
        markDirty();
    }

    @Override
    public boolean remove(Object o) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> { super.remove(o); markDirty(); });
            return true;
        }
        boolean r = super.remove(o);
        markDirty();
        return r;
    }

    @Override
    public E remove(int index) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> { super.remove(index); markDirty(); });
            return null;
        }
        E r = super.remove(index);
        markDirty();
        return r;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (isWorker()) {
            List<E> copy = List.copyOf(c);
            DeferredActionQueue.enqueue(() -> { super.addAll(copy); markDirty(); });
            return true;
        }
        boolean r = super.addAll(c);
        markDirty();
        return r;
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        if (isWorker()) {
            List<E> copy = List.copyOf(c);
            DeferredActionQueue.enqueue(() -> { super.addAll(index, copy); markDirty(); });
            return true;
        }
        boolean r = super.addAll(index, c);
        markDirty();
        return r;
    }

    @Override
    public boolean removeAll(@NonNull Collection<?> c) {
        if (isWorker()) {
            Set<?> copy = Set.copyOf(c);
            DeferredActionQueue.enqueue(() -> { super.removeAll(copy); markDirty(); });
            return true;
        }
        boolean r = super.removeAll(c);
        markDirty();
        return r;
    }

    @Override
    public boolean retainAll(@NonNull Collection<?> c) {
        if (isWorker()) {
            Set<?> copy = Set.copyOf(c);
            DeferredActionQueue.enqueue(() -> { super.retainAll(copy); markDirty(); });
            return true;
        }
        boolean r = super.retainAll(c);
        markDirty();
        return r;
    }

    @Override
    public void clear() {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> { super.clear(); markDirty(); });
            return;
        }
        super.clear();
        markDirty();
    }

    @Override
    public E set(int index, E element) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                if (index < super.size()) {
                    super.set(index, element);
                    markDirty();
                }
            });
            return null;
        }
        E r = super.set(index, element);
        markDirty();
        return r;
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> { super.removeIf(filter); markDirty(); });
            return true;
        }
        boolean r = super.removeIf(filter);
        markDirty();
        return r;
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> { super.replaceAll(operator); markDirty(); });
            return;
        }
        super.replaceAll(operator);
        markDirty();
    }

    @Override
    public void sort(Comparator<? super E> c) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> { super.sort(c); markDirty(); });
            return;
        }
        super.sort(c);
        markDirty();
    }

    // --- Read operations: use snapshot from workers ---

    @Override
    public E get(int index) {
        if (isWorker()) {
            List<E> snap = safeSnapshot();
            return (index >= 0 && index < snap.size()) ? snap.get(index) : null;
        }
        return super.get(index);
    }

    @Override
    public int size() {
        return isWorker() ? safeSnapshot().size() : super.size();
    }

    @Override
    public boolean isEmpty() {
        return isWorker() ? safeSnapshot().isEmpty() : super.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return isWorker() ? safeSnapshot().contains(o) : super.contains(o);
    }

    @Override
    public int indexOf(Object o) {
        return isWorker() ? safeSnapshot().indexOf(o) : super.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return isWorker() ? safeSnapshot().lastIndexOf(o) : super.lastIndexOf(o);
    }

    @Override
    public @NonNull Iterator<E> iterator() {
        return isWorker() ? safeSnapshot().iterator() : super.iterator();
    }

    @Override
    public @NonNull ListIterator<E> listIterator() {
        return isWorker() ? safeSnapshot().listIterator() : super.listIterator();
    }

    @Override
    public @NonNull ListIterator<E> listIterator(int index) {
        return isWorker() ? safeSnapshot().listIterator(index) : super.listIterator(index);
    }

    @Override
    public Object @NonNull [] toArray() {
        return isWorker() ? safeSnapshot().toArray() : super.toArray();
    }

    @Override
    public <T> T @NonNull [] toArray(T @NonNull [] a) {
        return isWorker() ? safeSnapshot().toArray(a) : super.toArray(a);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        if (isWorker()) {
            safeSnapshot().forEach(action);
        } else {
            super.forEach(action);
        }
    }

    @Override
    public @NonNull List<E> subList(int fromIndex, int toIndex) {
        if (isWorker()) {
            List<E> snap = safeSnapshot();
            int end = Math.min(toIndex, snap.size());
            int start = Math.min(fromIndex, end);
            return new ArrayList<>(snap.subList(start, end));
        }
        return super.subList(fromIndex, toIndex);
    }

    @Override
    public boolean containsAll(@NonNull Collection<?> c) {
        if (isWorker()) {
            return new HashSet<>(safeSnapshot()).containsAll(c);
        }
        return super.containsAll(c);
    }
}
