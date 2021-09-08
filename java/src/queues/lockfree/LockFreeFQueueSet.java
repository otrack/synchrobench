package queues.lockfree;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lock-free Queue (linked list) implementation of the integer set
 * as part of the JDK. 
 * It builds upon the Michael and Scott algorithm, PODC 1996.
 * 
 * @author Vincent Gramoli
 *
 */
public class LockFreeFQueueSet<E> extends AbstractQueue<E> {

	private static final long serialVersionUID = 0001;

    /** The thread-private PRNG */
    final private static ThreadLocal<Random> s_random = new ThreadLocal<Random>() {
        @Override
        protected synchronized Random initialValue() {
            return new Random();
        }
    };


	private static class Node<E> {
		volatile E item;
		volatile Node<E> next;

		/**
		 * Constructs a new node.  Uses relaxed write because item can
		 * only be seen after publication via casNext.
		 */
		Node(E item) {
			UNSAFE.putObject(this, itemOffset, item);
		}

		boolean casItem(E cmp, E val) {
			return UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
		}

		void lazySetNext(Node<E> val) {
			UNSAFE.putOrderedObject(this, nextOffset, val);
		}

		boolean casNext(Node<E> cmp, Node<E> val) {
			return UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
		}

		// Unsafe mechanics

		private static final sun.misc.Unsafe UNSAFE;
		private static final long itemOffset;
		private static final long nextOffset;

		static {
			try {
				Field f = Unsafe.class.getDeclaredField("theUnsafe");
				f.setAccessible(true);
				UNSAFE = (Unsafe) f.get(null);
				Class<?> k = Node.class;
				itemOffset = UNSAFE.objectFieldOffset
						(k.getDeclaredField("item"));
				nextOffset = UNSAFE.objectFieldOffset
						(k.getDeclaredField("next"));
			} catch (Exception e) {
				throw new Error(e);
			}
		}
	}


	private transient volatile Node<E> head;

	private transient volatile Node<E> tail;

	public LockFreeFQueueSet() {
		tail = head = new Node<>(null);
	}

	@Override
	public Iterator<E> iterator() {
		throw new IllegalArgumentException();
	}

	@Override
	public int size() {
		int ret = 0;
		for (Node<E> p = head;;) {
			if (p.item != null) ret++;
			if (p.next==null) break;
			p = p.next;
		}
		return ret;
	}

	@Override
	public boolean offer(E e) {
		checkNotNull(e);
		final Node<E> newNode = new Node<E>(e);

		for (Node<E> t = tail, p = t;;) {
			Node<E> q = p.next;
			if (q == null) {
				// p is last node
				if (p.casNext(null, newNode)) {
					// Successful CAS is the linearization point
					// for e to become an element of this queue,
					// and for newNode to become "live".
					if (p != t) // hop two nodes at a time
						lazySetTail(newNode);
					return true;
				}
				// Lost CAS race to another thread; re-read next
			}
			else if (p == q)
				// We have fallen off list.  If tail is unchanged, it
				// will also be off-list, in which case we need to
				// jump to head, from which all live nodes are always
				// reachable.  Else the new tail is a better bet.
				p = (t != (t = tail)) ? t : head;
			else
				// Check for tail updates after two hops.
				p = (p != t && t != (t = tail)) ? t : q;
		}
	}

	@Override
	public E poll() {
		restartFromHead:
		for (;;) {
			for (Node<E> h = head, p = h, q;;) {
				E item = p.item;

				if (item != null && p.casItem(item, null)) {
					// Successful CAS is the linearization point
					// for item to be removed from this queue.
//					if (p != h) // hop two nodes at a time
//						updateHead(h, ((q = p.next) != null) ? q : p);
					if (p != h && p.next != null)
						head.lazySetNext(p.next);
					return item;
				}
				else if ((q = p.next) == null) {
					updateHead(h, p);
					return null;
				}
				else if (p == q)
					continue restartFromHead;
				else
					p = q;
			}
		}
	}

	@Override
	public E peek() {
		throw new IllegalArgumentException();
	}

	/**
	 * Tries to CAS head to p. If successful, repoint old head to itself
	 * as sentinel for succ(), below.
	 */
	final void updateHead(Node<E> h, Node<E> p) {
		if (h != p && casHead(h, p))
			h.lazySetNext(h);
	}


	private boolean casHead(Node<E> cmp, Node<E> val) {
		return UNSAFE.compareAndSwapObject(this, headOffset, cmp, val);
	}

	private boolean casTail(Node<E> cmp, Node<E> val) {
		return UNSAFE.compareAndSwapObject(this, tailOffset, cmp, val);
	}

	private void lazySetTail(Node<E> val) {
		UNSAFE.putOrderedObject(this, tailOffset, val);
	}


	private static void checkNotNull(Object v) {
		if (v == null)
			throw new NullPointerException();
	}

	// Unsafe mechanics

	private static final sun.misc.Unsafe UNSAFE;
	private static final long headOffset;
	private static final long tailOffset;
	static {
		try {
			Field f = Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			UNSAFE = (Unsafe) f.get(null);
			Class<?> k = ConcurrentLinkedQueue.class;
			headOffset = UNSAFE.objectFieldOffset
					(k.getDeclaredField("head"));
			tailOffset = UNSAFE.objectFieldOffset
					(k.getDeclaredField("tail"));
		} catch (Exception e) {
			e.printStackTrace();
			throw new Error(e);
		}
	}
}
