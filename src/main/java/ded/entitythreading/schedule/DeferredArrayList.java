package ded.entitythreading.schedule;

import org.jspecify.annotations.NonNull;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public class DeferredArrayList<E> extends ArrayList<E> {

    private final CopyOnWriteArrayList<E> snapshot = new CopyOnWriteArrayList<>();
    private volatile boolean snapshotDirty = true;

    public DeferredArrayList(Collection<? extends E> c) {
        super(c);
    }

    private static boolean isWorker() {
        return EntityWorkerThread.isCurrentThreadWorker();
    }

    private void markDirty() {
        snapshotDirty = true;
    }

    private void rebuildSnapshot() {
        if (snapshotDirty) {
            synchronized (this) {
                if (snapshotDirty) {
                    Object[] raw = super.toArray();
                    snapshot.clear();
                    for (Object o : raw) {
                        snapshot.add((E) o);
                    }
                    snapshotDirty = false;
                }
            }
        }
    }

    @Override
    public boolean add(E e) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                super.add(e);
                markDirty();
            });
            return true;
        }
        boolean r = super.add(e);
        markDirty();
        return r;
    }

    @Override
    public void add(int index, E element) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                super.add(index, element);
                markDirty();
            });
            return;
        }
        super.add(index, element);
        markDirty();
    }

    @Override
    public boolean remove(Object o) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                super.remove(o);
                markDirty();
            });
            return true;
        }
        boolean r = super.remove(o);
        markDirty();
        return r;
    }

    @Override
    public E remove(int index) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                super.remove(index);
                markDirty();
            });
            return null;
        }
        E r = super.remove(index);
        markDirty();
        return r;
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (isWorker()) {
            ArrayList<E> copy = new ArrayList<>(c);
            DeferredActionQueue.enqueue(() -> {
                super.addAll(copy);
                markDirty();
            });
            return true;
        }
        boolean r = super.addAll(c);
        markDirty();
        return r;
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        if (isWorker()) {
            ArrayList<E> copy = new ArrayList<>(c);
            DeferredActionQueue.enqueue(() -> {
                super.addAll(index, copy);
                markDirty();
            });
            return true;
        }
        boolean r = super.addAll(index, c);
        markDirty();
        return r;
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        if (isWorker()) {
            ArrayList<?> copy = new ArrayList<>(c);
            DeferredActionQueue.enqueue(() -> {
                super.removeAll(copy);
                markDirty();
            });
            return true;
        }
        boolean r = super.removeAll(c);
        markDirty();
        return r;
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        if (isWorker()) {
            ArrayList<?> copy = new ArrayList<>(c);
            DeferredActionQueue.enqueue(() -> {
                super.retainAll(copy);
                markDirty();
            });
            return true;
        }
        boolean r = super.retainAll(c);
        markDirty();
        return r;
    }

    @Override
    public void clear() {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                super.clear();
                markDirty();
            });
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
    public E get(int index) {
        if (isWorker()) {
            rebuildSnapshot();
            return (index >= 0 && index < snapshot.size()) ? snapshot.get(index) : null;
        }
        return super.get(index);
    }

    @Override
    public int size() {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.size();
        }
        return super.size();
    }

    @Override
    public boolean isEmpty() {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.isEmpty();
        }
        return super.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.contains(o);
        }
        return super.contains(o);
    }

    @Override
    public int indexOf(Object o) {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.indexOf(o);
        }
        return super.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.lastIndexOf(o);
        }
        return super.lastIndexOf(o);
    }

    @Override
    public @NonNull Iterator<E> iterator() {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.iterator();
        }
        return super.iterator();
    }

    @Override
    public @NonNull ListIterator<E> listIterator() {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.listIterator();
        }
        return super.listIterator();
    }

    @Override
    public @NonNull ListIterator<E> listIterator(int index) {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.listIterator(index);
        }
        return super.listIterator(index);
    }

    @Override
    public Object @NonNull [] toArray() {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.toArray();
        }
        return super.toArray();
    }

    @Override
    public <T> T @NonNull [] toArray(T[] a) {
        if (isWorker()) {
            rebuildSnapshot();
            return snapshot.toArray(a);
        }
        return super.toArray(a);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        if (isWorker()) {
            rebuildSnapshot();
            snapshot.forEach(action);
            return;
        }
        super.forEach(action);
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                super.removeIf(filter);
                markDirty();
            });
            return true;
        }
        boolean r = super.removeIf(filter);
        markDirty();
        return r;
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                super.replaceAll(operator);
                markDirty();
            });
            return;
        }
        super.replaceAll(operator);
        markDirty();
    }

    @Override
    public void sort(Comparator<? super E> c) {
        if (isWorker()) {
            DeferredActionQueue.enqueue(() -> {
                super.sort(c);
                markDirty();
            });
            return;
        }
        super.sort(c);
        markDirty();
    }

    @Override
    public @NonNull List<E> subList(int fromIndex, int toIndex) {
        if (isWorker()) {
            rebuildSnapshot();
            int end = Math.min(toIndex, snapshot.size());
            int start = Math.min(fromIndex, end);
            return new ArrayList<>(snapshot.subList(start, end));
        }
        return super.subList(fromIndex, toIndex);
    }

    @Override
    public boolean containsAll(@NonNull Collection<?> c) {
        if (isWorker()) {
            rebuildSnapshot();
            return new HashSet<>(snapshot).containsAll(c);
        }
        return super.containsAll(c);
    }
}
