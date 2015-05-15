/*
 * Copyright (c) 2015 Pantheon Technologies s.r.o. and others. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.openflowjava.protocol.impl.core.connection;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.locks.LockSupport;
import javax.annotation.Nonnull;
import org.opendaylight.openflowjava.protocol.api.connection.OutboundQueue;
import org.opendaylight.yang.gen.v1.urn.opendaylight.openflow.protocol.rev130731.OfHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OutboundQueueImpl implements OutboundQueue {
    private static final Logger LOG = LoggerFactory.getLogger(OutboundQueueImpl.class);
    private static final AtomicIntegerFieldUpdater<OutboundQueueImpl> CURRENT_OFFSET_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(OutboundQueueImpl.class, "reserveOffset");
    private static final AtomicIntegerFieldUpdater<OutboundQueueImpl> BARRIER_OFFSET_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(OutboundQueueImpl.class, "barrierOffset");
    private static final long FLUSH_RETRY_NANOS = 1L;
    private final OutboundQueueManager<?> manager;
    private final OutboundQueueEntry[] queue;
    private final long baseXid;
    private final long endXid;
    private final int reserve;

    // Updated concurrently
    private volatile int barrierOffset = -1;
    private volatile int reserveOffset = 0;

    // Updated from Netty only
    private int flushOffset;
    private int completeCount;

    OutboundQueueImpl(final OutboundQueueManager<?> manager, final long baseXid, final int maxQueue) {
        /*
         * We use the last entry as an emergency should a timeout-triggered
         * flush request race with normal users for the last entry in this
         * queue. In that case the flush request will take the last entry and
         * schedule a flush, which means that we will get around sending the
         * message as soon as the user finishes the reservation.
         */
        Preconditions.checkArgument(maxQueue > 1);
        this.baseXid = baseXid;
        this.endXid = baseXid + maxQueue;
        this.reserve = maxQueue - 1;
        this.manager = Preconditions.checkNotNull(manager);
        queue = new OutboundQueueEntry[maxQueue];
        for (int i = 0; i < maxQueue; ++i) {
            queue[i] = new OutboundQueueEntry();
        }
    }

    private OutboundQueueImpl(final OutboundQueueManager<?> manager, final long baseXid, final OutboundQueueEntry[] queue) {
        this.manager = Preconditions.checkNotNull(manager);
        this.queue = Preconditions.checkNotNull(queue);
        this.baseXid = baseXid;
        this.endXid = baseXid + queue.length;
        this.reserve = queue.length - 1;
        for (OutboundQueueEntry element : queue) {
            element.reset();
        }
    }

    OutboundQueueImpl reuse(final long baseXid) {
        return new OutboundQueueImpl(manager, baseXid, queue);
    }

    @Override
    public Long reserveEntry() {
        return reserveEntry(false);
    }

    @Override
    public void commitEntry(final Long xid, final OfHeader message, final FutureCallback<OfHeader> callback) {
        final int offset = (int)(xid - baseXid);
        if (message != null) {
            Preconditions.checkArgument(xid.equals(message.getXid()), "Message %s has wrong XID %s, expected %s", message, message.getXid(), xid);
        }

        final OutboundQueueEntry entry = queue[offset];
        entry.commit(message, callback);
        LOG.debug("Queue {} XID {} at offset {} (of {}) committed", this, xid, offset, reserveOffset);

        if (entry.isBarrier()) {
            int my = offset;
            for (;;) {
                final int prev = BARRIER_OFFSET_UPDATER.getAndSet(this, my);
                if (prev < my) {
                    LOG.debug("Queue {} recorded pending barrier offset {}", this, my);
                    break;
                }

                // We have traveled back, recover
                my = prev;
            }
        }

        manager.ensureFlushing(this);
    }

    private Long reserveEntry(final boolean forBarrier) {
        final int offset = CURRENT_OFFSET_UPDATER.getAndIncrement(this);
        if (offset >= reserve) {
            if (forBarrier) {
                LOG.debug("Queue {} offset {}/{}, using emergency slot", this, offset, queue.length);
                return endXid;
            } else {
                LOG.debug("Queue {} offset {}/{}, not allowing reservation", this, offset, queue.length);
                return null;
            }
        }

        final Long xid = baseXid + offset;
        LOG.debug("Queue {} allocated XID {} at offset {}", this, xid, offset);
        return xid;
    }

    Long reserveBarrierIfNeeded() {
        final int bo = barrierOffset;
        if (bo >= flushOffset) {
            LOG.debug("Barrier found at offset {} (currently at {})", bo, flushOffset);
            return null;
        } else {
            return reserveEntry(true);
        }
    }

    /**
     * An empty queue is a queue which has no further unflushed entries.
     *
     * @return True if this queue does not have unprocessed entries.
     */
    boolean isEmpty() {
        int ro = reserveOffset;
        if (ro >= reserve) {
            if (queue[reserve].isCommitted()) {
                ro = reserve + 1;
            } else {
                ro = reserve;
            }
        }

        LOG.debug("Effective flush/reserve offset {}/{}", flushOffset, ro);
        return ro <= flushOffset;
    }

    /**
     * A queue is finished when all of its entries have been completed.
     *
     * @return False if there are any uncompleted requests.
     */
    boolean isFinished() {
        if (completeCount < reserve) {
            return false;
        }

        // We need to check if the last entry was used
        final OutboundQueueEntry last = queue[reserve];
        return !last.isCommitted() || last.isCompleted();
    }

    boolean isFlushed() {
        LOG.debug("Check queue {} for completeness (offset {}, reserve {})", flushOffset, reserve);
        if (flushOffset < reserve) {
            return false;
        }

        // flushOffset implied == reserve
        return flushOffset >= queue.length || !queue[reserve].isCommitted();
    }

    OfHeader flushEntry() {
        for (;;) {
            // No message ready
            if (isEmpty()) {
                LOG.debug("Flush offset {} is uptodate with reserved", flushOffset);
                return null;
            }

            boolean retry = true;
            while (!queue[flushOffset].isCommitted()) {
                if (!retry) {
                    LOG.debug("Offset {} not ready yet, giving up", flushOffset);
                    return null;
                }

                LOG.debug("Offset {} not ready yet, retrying", flushOffset);
                LockSupport.parkNanos(FLUSH_RETRY_NANOS);
                retry = false;
            }

            final OfHeader msg = queue[flushOffset++].getMessage();
            if (msg != null) {
                return msg;
            }
        }
    }

    private boolean xidInRance(final long xid) {
        return xid < endXid && (xid >= baseXid || baseXid > endXid);
    }

    /**
     * Return the request entry corresponding to a response. Returns null
     * if there is no request matching the response.
     *
     * @param response Response message
     * @return Matching request entry, or null if no match is found.
     */
    OutboundQueueEntry pairRequest(@Nonnull final OfHeader response) {
        final Long xid = response.getXid();
        if (!xidInRance(xid)) {
            LOG.debug("Queue {} {}/{} ignoring XID {}", this, baseXid, queue.length, xid);
            return null;
        }

        final int offset = (int)(xid - baseXid);
        final OutboundQueueEntry entry = queue[offset];
        if (entry.isCompleted()) {
            LOG.debug("Entry {} already is completed, not accepting response {}", entry, response);
            return null;
        }

        if (entry.complete(response)) {
            completeCount++;

            // This has been a barrier -- make sure we complete all preceding requests
            if (entry.isBarrier()) {
                LOG.debug("Barrier XID {} completed, cascading completion to XIDs {} to {}", xid, baseXid, xid - 1);
                for (int i = 0; i < offset; ++i) {
                    final OutboundQueueEntry e = queue[i];
                    if (!e.isCompleted() && e.complete(null)) {
                        completeCount++;
                    }
                }
            }
        }
        return entry;
    }

    void completeAll() {
        for (OutboundQueueEntry entry : queue) {
            if (!entry.isCompleted() && entry.complete(null)) {
                completeCount++;
            }
        }
    }

    int failAll(final Throwable cause) {
        int ret = 0;
        for (OutboundQueueEntry entry : queue) {
            if (!entry.isCompleted()) {
                entry.fail(cause);
                ret++;
            }
        }

        return ret;
    }
}