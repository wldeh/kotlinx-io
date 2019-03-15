package kotlinx.io.core.internal

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.atomicfu.updateAndGet
import kotlinx.io.bits.*
import kotlinx.io.bits.DefaultAllocator
import kotlinx.io.core.*
import kotlinx.io.core.DefaultChunkedBufferPool
import kotlinx.io.pool.*

@DangerousInternalIoApi
open class ChunkBuffer internal constructor(memory: Memory, origin: ChunkBuffer?) : Buffer(memory) {
    init {
        require(origin !== this) { "A chunk couldn't be a view of itself." }
    }

    private val nextRef: AtomicRef<ChunkBuffer?> = atomic(null)
    private val refCount = atomic(1)

    /**
     * Reference to an origin buffer view this was copied from
     */
    var origin: ChunkBuffer? = origin
        private set

    /**
     * Reference to next buffer view. Useful to chain multiple views.
     * @see appendNext
     * @see cleanNext
     */
    var next: ChunkBuffer? get() = nextRef.value
        set(newValue) {
            if (newValue == null) {
                cleanNext()
            } else {
                appendNext(newValue)
            }
        }

    val referenceCount: Int get() = refCount.value

    fun appendNext(chunk: ChunkBuffer) {
        if (!nextRef.compareAndSet(null, chunk)) {
            throw IllegalStateException("This chunk has already a next chunk.")
        }
    }

    fun cleanNext(): ChunkBuffer? {
        return nextRef.getAndSet(null)
    }

    override fun duplicate(): ChunkBuffer = (origin ?: this).let { newOrigin ->
        newOrigin.acquire()
        ChunkBuffer(memory, newOrigin).also { copy ->
            duplicateTo(copy)
        }
    }

    open fun release(pool: ObjectPool<ChunkBuffer>) {
        if (release()) {
            val origin = origin
            if (origin != null) {
                unlink()
                origin.release(pool)
            } else {
                pool.recycle(this)
            }
        }
    }

    internal fun unlink() {
        if (!refCount.compareAndSet(0, -1)) {
            throw IllegalStateException("Unable to unlink: buffer is in use.")
        }

        cleanNext()
        origin = null
    }

    /**
     * Increase ref-count. May fail if already released.
     */
    internal fun acquire() {
        refCount.update { old ->
            if (old <= 0) throw IllegalStateException("Unable to acquire chunk: it is already released.")
            old + 1
        }
    }

    /**
     * Invoked by a pool before return the instance to a user.
     */
    internal fun unpark() {
        refCount.update { old ->
            if (old < 0) {
                throw IllegalStateException("This instance is already disposed and couldn't be borrowed.")
            }
            if (old > 0) {
                throw IllegalStateException("This instance is already in use but somehow appeared in the pool.")
            }

            1
        }
    }

    /**
     * Release ref-count.
     * @return `true` if the last usage was released
     */
    internal fun release(): Boolean {
        return refCount.updateAndGet { old ->
            if (old <= 0) throw IllegalStateException("Unable to release: it is already released.")
            old - 1
        } == 0
    }

    companion object {
        val Pool: ObjectPool<ChunkBuffer> get() = DefaultChunkedBufferPool
        val Empty: ChunkBuffer = ChunkBuffer(Memory.Empty, null)

        /**
         * A pool that always returns [ChunkBuffer.Empty]
         */
        val EmptyPool: ObjectPool<ChunkBuffer> = object : ObjectPool<ChunkBuffer> {
            override val capacity: Int get() = 1

            override fun borrow() = Empty

            override fun recycle(instance: ChunkBuffer) {
                require(instance === ChunkBuffer.Empty) { "Only ChunkBuffer.Empty instance could be recycled." }
            }

            override fun dispose() {
            }
        }

        internal val NoPool: ObjectPool<ChunkBuffer> = object : NoPoolImpl<ChunkBuffer>() {
            private val allocator = DefaultAllocator

            override fun borrow(): ChunkBuffer {
                return ChunkBuffer(allocator.alloc(DEFAULT_BUFFER_SIZE), null)
            }

            override fun recycle(instance: ChunkBuffer) {
                allocator.free(instance.memory)
            }
        }
    }
}


/**
 * @return `true` if and only if the are no buffer views that share the same actual buffer. This actually does
 * refcount and only work guaranteed if other views created/not created via [Buffer.duplicate] function.
 * One can instantiate multiple buffers with the same buffer and this function will return `true` in spite of
 * the fact that the buffer is actually shared.
 */
internal fun ChunkBuffer.isExclusivelyOwned(): Boolean = referenceCount == 1
