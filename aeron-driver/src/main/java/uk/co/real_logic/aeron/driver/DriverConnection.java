/*
 * Copyright 2014 - 2015 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.driver;

import uk.co.real_logic.aeron.common.protocol.DataHeaderFlyweight;
import uk.co.real_logic.aeron.driver.buffer.RawLogPartition;
import uk.co.real_logic.agrona.concurrent.NanoClock;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.LogRebuilder;
import uk.co.real_logic.aeron.common.event.EventLogger;
import uk.co.real_logic.agrona.status.PositionIndicator;
import uk.co.real_logic.agrona.status.PositionReporter;
import uk.co.real_logic.aeron.driver.buffer.RawLog;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor.lengthOffset;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.LogBufferDescriptor.*;
import static uk.co.real_logic.aeron.driver.DriverConnection.Status.ACTIVE;

/**
 * State maintained for active sessionIds within a channel for receiver processing
 */
public class DriverConnection implements AutoCloseable
{
    public enum Status
    {
        INIT, ACTIVE, INACTIVE, LINGER
    }

    private final long correlationId;
    private final long statusMessageTimeout;
    private final int sessionId;
    private final int streamId;
    private final int positionBitsToShift;
    private final int termLengthMask;
    private final int initialTermId;
    private final int currentWindowLength;
    private final int currentGain;

    private final RawLog rawLog;
    private final ReceiveChannelEndpoint channelEndpoint;
    private final EventLogger logger;
    private final SystemCounters systemCounters;
    private final NanoClock clock;
    private final UnsafeBuffer[] termBuffers;
    private final AtomicLong timeOfLastFrame = new AtomicLong();
    private final PositionReporter completedPosition;
    private final PositionReporter hwmPosition;
    private final InetSocketAddress sourceAddress;
    private final List<PositionIndicator> subscriberPositions;
    private final AtomicLong subscribersPosition = new AtomicLong();
    private final LossHandler lossHandler;
    private final StatusMessageSender statusMessageSender;

    private long timeOfLastStatusChange;
    private long lastSmPosition;
    private long lastSmTimestamp;

    private volatile Status status = Status.INIT;

    public DriverConnection(
        final ReceiveChannelEndpoint channelEndpoint,
        final long correlationId,
        final int sessionId,
        final int streamId,
        final int initialTermId,
        final int activeTermId,
        final int initialTermOffset,
        final int initialWindowLength,
        final long statusMessageTimeout,
        final RawLog rawLog,
        final LossHandler lossHandler,
        final StatusMessageSender statusMessageSender,
        final List<PositionIndicator> subscriberPositions,
        final PositionReporter completedPosition,
        final PositionReporter hwmPosition,
        final NanoClock clock,
        final SystemCounters systemCounters,
        final InetSocketAddress sourceAddress,
        final EventLogger logger)
    {
        this.channelEndpoint = channelEndpoint;
        this.correlationId = correlationId;
        this.sessionId = sessionId;
        this.streamId = streamId;
        this.rawLog = rawLog;
        this.subscriberPositions = subscriberPositions;
        this.completedPosition = completedPosition;
        this.hwmPosition = hwmPosition;
        this.systemCounters = systemCounters;
        this.sourceAddress = sourceAddress;
        this.logger = logger;

        this.clock = clock;
        final long time = clock.time();
        this.timeOfLastStatusChange = time;
        timeOfLastFrame.lazySet(time);

        termBuffers = rawLog.stream().map(RawLogPartition::termBuffer).toArray(UnsafeBuffer[]::new);
        this.lossHandler = lossHandler;
        this.statusMessageSender = statusMessageSender;
        this.statusMessageTimeout = statusMessageTimeout;
        this.lastSmTimestamp = 0;

        final int termCapacity = termBuffers[0].capacity();

        this.currentWindowLength = Math.min(termCapacity, initialWindowLength);
        this.currentGain = Math.min(currentWindowLength / 4, termCapacity / 4);

        this.termLengthMask = termCapacity - 1;
        this.positionBitsToShift = Integer.numberOfTrailingZeros(termCapacity);
        this.initialTermId = initialTermId;

        final long initialPosition = computePosition(activeTermId, initialTermOffset, positionBitsToShift, initialTermId);
        this.lastSmPosition = initialPosition - (currentGain + 1);
        this.completedPosition.position(initialPosition);
        this.hwmPosition.position(initialPosition);
    }

    /**
     * {@inheritDoc}
     */
    public void close()
    {
        completedPosition.close();
        hwmPosition.close();
        rawLog.close();
        subscriberPositions.forEach(PositionIndicator::close);
    }

    public long correlationId()
    {
        return correlationId;
    }

    /**
     * The {@link ReceiveChannelEndpoint} to which the connection belongs.
     *
     * @return {@link ReceiveChannelEndpoint} to which the connection belongs.
     */
    public ReceiveChannelEndpoint receiveChannelEndpoint()
    {
        return channelEndpoint;
    }

    /**
     * The session id of the channel from a publisher.
     *
     * @return session id of the channel from a publisher.
     */
    public int sessionId()
    {
        return sessionId;
    }

    /**
     * The stream id of this connection within a channel.
     *
     * @return stream id of this connection within a channel.
     */
    public int streamId()
    {
        return streamId;
    }

    /**
     * The address of the source associated with the connection.
     *
     * @return source address
     */
    public InetSocketAddress sourceAddress()
    {
        return sourceAddress;
    }

    /**
     * Does this connection match a given {@link ReceiveChannelEndpoint} and stream id?
     *
     * @param channelEndpoint to match by identity.
     * @param streamId        to match on value.
     * @return true on a match otherwise false.
     */
    public boolean matches(final ReceiveChannelEndpoint channelEndpoint, final int streamId)
    {
        return this.streamId == streamId && this.channelEndpoint == channelEndpoint;
    }

    /**
     * Get the {@link uk.co.real_logic.aeron.driver.buffer.RawLog} the back this connection.
     *
     * @return the {@link uk.co.real_logic.aeron.driver.buffer.RawLog} the back this connection.
     */
    public RawLog rawLogBuffers()
    {
        return rawLog;
    }

    /**
     * Return status of the connection. Retrieved by {@link DriverConductor}.
     *
     * @return status of the connection
     */
    public Status status()
    {
        return status;
    }

    /**
     * Set status of the connection. Set by {@link DriverConductor}.
     *
     * @param status of the connection
     */
    public void status(final Status status)
    {
        this.status = status;
    }

    /**
     * Return time of last status change. Retrieved by {@link DriverConductor}.
     *
     * @return time of last status change
     */
    public long timeOfLastStatusChange()
    {
        return timeOfLastStatusChange;
    }

    /**
     * Set time of last status change. Set by {@link DriverConductor}.
     *
     * @param now timestamp to use for time
     */
    public void timeOfLastStatusChange(final long now)
    {
        timeOfLastStatusChange = now;
    }

    /**
     * Called from the {@link DriverConductor}.
     *
     * @return if work has been done or not
     */
    public int trackCompletion()
    {
        long minSubscriberPosition = Long.MAX_VALUE;
        long maxSubscriberPosition = Long.MIN_VALUE;

        final List<PositionIndicator> subscriberPositions = this.subscriberPositions;
        for (int i = 0, size = subscriberPositions.size(); i < size; i++)
        {
            final long position = subscriberPositions.get(i).position();
            minSubscriberPosition = Math.min(minSubscriberPosition, position);
            maxSubscriberPosition = Math.max(maxSubscriberPosition, position);
        }

        subscribersPosition.lazySet(minSubscriberPosition);

        final long oldCompletedPosition = this.completedPosition.position();
        final long completedPosition = Math.max(oldCompletedPosition, maxSubscriberPosition);

        final int positionBitsToShift = this.positionBitsToShift;
        final int index = indexByPosition(completedPosition, positionBitsToShift);

        int workCount = lossHandler.scan(
            termBuffers[index], completedPosition, hwmPosition.position(), termLengthMask, positionBitsToShift, initialTermId);

        final int completedTermOffset = (int)completedPosition & termLengthMask;
        final int completedOffset = lossHandler.completedOffset();
        final long newCompletedPosition = (completedPosition - completedTermOffset) + completedOffset;
        this.completedPosition.position(newCompletedPosition);

        if ((newCompletedPosition >>> positionBitsToShift) > (oldCompletedPosition >>> positionBitsToShift))
        {
            final UnsafeBuffer termBuffer = termBuffers[previousPartitionIndex(index)];
            termBuffer.setMemory(0, termBuffer.capacity(), (byte)0);
        }

        return workCount;
    }

    /**
     * Called from the {@link DriverConductor} to determine what is remaining for the subscriber to drain.
     *
     * @return remaining bytes to drain
     */
    public long remaining()
    {
        return Math.max(completedPosition.position() - subscribersPosition.get(), 0);
    }

    /**
     * Insert frame into term buffer.
     *
     * @param buffer for the data packet to insert into the appropriate term.
     * @param length of the data packet
     * @return number of bytes applied as a result of this insertion.
     */
    public int insertPacket(final int termId, final int termOffset, final UnsafeBuffer buffer, final int length)
    {
        int bytesReceived = length;
        final int positionBitsToShift = this.positionBitsToShift;
        final long packetBeginPosition = computePosition(termId, termOffset, positionBitsToShift, initialTermId);
        final long proposedPosition = packetBeginPosition + length;
        final long completedPosition = this.completedPosition.position();

        if (isHeartbeat(buffer, length))
        {
            hwmCandidate(packetBeginPosition);
            systemCounters.heartbeatsReceived().orderedIncrement();
        }
        else if (isFlowControlUnderRun(completedPosition, packetBeginPosition) || isFlowControlOverRun(proposedPosition))
        {
            bytesReceived = 0;
        }
        else
        {
            final UnsafeBuffer termBuffer = termBuffers[indexByPosition(packetBeginPosition, positionBitsToShift)];
            LogRebuilder.insert(termBuffer, termOffset, buffer, 0, length);

            hwmCandidate(proposedPosition);
        }

        return bytesReceived;
    }

    /**
     * Called from the {@link DriverConductor}.
     *
     * @param now time in nanoseconds
     * @return number of work items processed.
     */
    public int sendPendingStatusMessage(final long now)
    {
        int workCount = 1;
        if (ACTIVE == status)
        {
            final long position = subscribersPosition.get();

            if ((position - lastSmPosition) > currentGain || now > (lastSmTimestamp + statusMessageTimeout))
            {
                final int activeTermId = computeTermIdFromPosition(position, positionBitsToShift, initialTermId);
                final int termOffset = (int)position & termLengthMask;

                statusMessageSender.send(activeTermId, termOffset, currentWindowLength);

                lastSmTimestamp = now;
                lastSmPosition = position;
                systemCounters.statusMessagesSent().orderedIncrement();

                // invert the work count logic. We want to appear to be less busy once we send an SM
                workCount = 0;
            }
        }

        return workCount;
    }

    /**
     * Called from the {@link DriverConductor} thread to grab the time of the last frame for liveness
     *
     * @return time of last frame from the source
     */
    public long timeOfLastFrame()
    {
        return timeOfLastFrame.get();
    }

    /**
     * Remove a {@link PositionIndicator} for a subscriber that has been removed so it is not tracked for flow control.
     *
     * @param subscriberPosition for the subscriber that has been removed.
     */
    public void removeSubscription(final PositionIndicator subscriberPosition)
    {
        subscriberPositions.remove(subscriberPosition);
        subscriberPosition.close();
    }

    /**
     * Add a new subscriber to this connection so their position can be tracked for flow control.
     *
     * @param subscriberPosition for the subscriber to be added.
     */
    public void addSubscription(final PositionIndicator subscriberPosition)
    {
        subscriberPositions.add(subscriberPosition);
    }

    /**
     * The position up to which the current stream rebuild is complete for reception.
     *
     * @return the position up to which the current stream rebuild is complete for reception.
     */
    public long completedPosition()
    {
        return completedPosition.position();
    }

    /**
     * The initial term id this connection started at.
     *
     * @return the initial term id this connection started at.
     */
    public int initialTermId()
    {
        return initialTermId;
    }

    private boolean isHeartbeat(final UnsafeBuffer buffer, final int length)
    {
        return length == DataHeaderFlyweight.HEADER_LENGTH && buffer.getInt(lengthOffset(0)) == 0;
    }

    private void hwmCandidate(final long proposedPosition)
    {
        timeOfLastFrame.lazySet(clock.time());

        if (proposedPosition > hwmPosition.position())
        {
            hwmPosition.position(proposedPosition);
        }
    }

    private boolean isFlowControlUnderRun(final long completedPosition, final long packetPosition)
    {
        final boolean isFlowControlUnderRun = packetPosition < completedPosition;

        if (isFlowControlUnderRun)
        {
            systemCounters.flowControlUnderRuns().orderedIncrement();
        }

        return isFlowControlUnderRun;
    }

    private boolean isFlowControlOverRun(final long proposedPosition)
    {
        final long subscribersPosition = this.subscribersPosition.get();
        boolean isFlowControlOverRun = proposedPosition > (subscribersPosition + currentWindowLength);

        if (isFlowControlOverRun)
        {
            logger.logOverRun(proposedPosition, subscribersPosition, currentWindowLength);
            systemCounters.flowControlOverRuns().orderedIncrement();
        }

        return isFlowControlOverRun;
    }
}
