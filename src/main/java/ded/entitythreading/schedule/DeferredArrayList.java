package ded.entitythreading.schedule;

import java.util.ArrayList;
import java.util.Collection;

/**
 * A drop-in replacement for Vanilla's internal World lists.
 * If any mod attempts to directly add/remove entities or tile entities
 * while running on a worker thread, this intercepts the modification
 * and safely defers it to the main thread. At least that's what it's meant for.
 *
 * This should prevent ArrayList corruption, ConcurrentModificationExceptions,
 * and NullPointerExceptions caused by non-thread-safe mods.
 */
public class DeferredArrayList<E> extends ArrayList<E> {

    public DeferredArrayList(Collection<? extends E> c) {
        super(c);
    }

    public DeferredArrayList() {
        super();
    }

    @Override
    public boolean add(E e) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> super.add(e));
            return true;
        }
        return super.add(e);
    }

    @Override
    public void add(int index, E element) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> super.add(index, element));
            return;
        }
        super.add(index, element);
    }

    @Override
    public boolean remove(Object o) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> super.remove(o));
            return true;
        }
        return super.remove(o);
    }

    @Override
    public E remove(int index) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> super.remove(index));
            return null; // Return null safely, actual removal happens later
        }
        return super.remove(index);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        if (EntityTickScheduler.isEntityThread()) {
            ArrayList<E> clone = new ArrayList<>(c);
            DeferredActionQueue.enqueue(() -> super.addAll(clone));
            return true;
        }
        return super.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        if (EntityTickScheduler.isEntityThread()) {
            ArrayList<E> clone = new ArrayList<>(c);
            DeferredActionQueue.enqueue(() -> super.addAll(index, clone));
            return true;
        }
        return super.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        if (EntityTickScheduler.isEntityThread()) {
            ArrayList<?> clone = new ArrayList<>(c);
            DeferredActionQueue.enqueue(() -> super.removeAll(clone));
            return true;
        }
        return super.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        if (EntityTickScheduler.isEntityThread()) {
            ArrayList<?> clone = new ArrayList<>(c);
            DeferredActionQueue.enqueue(() -> super.retainAll(clone));
            return true;
        }
        return super.retainAll(c);
    }

    @Override
    public void clear() {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(super::clear);
            return;
        }
        super.clear();
    }

    @Override
    public E set(int index, E element) {
        if (EntityTickScheduler.isEntityThread()) {
            DeferredActionQueue.enqueue(() -> super.set(index, element));
            return null;
        }
        return super.set(index, element);
    }
}