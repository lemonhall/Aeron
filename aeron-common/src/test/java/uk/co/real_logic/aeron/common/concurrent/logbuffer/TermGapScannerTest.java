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
package uk.co.real_logic.aeron.common.concurrent.logbuffer;

import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.aeron.common.protocol.DataHeaderFlyweight;
import uk.co.real_logic.agrona.concurrent.UnsafeBuffer;

import static java.lang.Boolean.*;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor.FRAME_ALIGNMENT;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.FrameDescriptor.lengthOffset;

public class TermGapScannerTest
{
    private static final int LOG_BUFFER_CAPACITY = LogBufferDescriptor.TERM_MIN_LENGTH;
    private static final int TERM_ID = 1;

    private final UnsafeBuffer termBuffer = mock(UnsafeBuffer.class);
    private final TermGapScanner.GapHandler gapHandler = mock(TermGapScanner.GapHandler.class);

    @Before
    public void setUp()
    {
        when(termBuffer.capacity()).thenReturn(LOG_BUFFER_CAPACITY);
    }

    @Test
    public void shouldReportGapAtBeginningOfBuffer()
    {
        final int frameOffset = FRAME_ALIGNMENT * 3;
        final int highWaterMark = frameOffset + DataHeaderFlyweight.HEADER_LENGTH;

        when(termBuffer.getIntVolatile(lengthOffset(frameOffset)))
            .thenReturn(DataHeaderFlyweight.HEADER_LENGTH);

        assertThat(TermGapScanner.scanForGap(termBuffer, TERM_ID, 0, highWaterMark, gapHandler), is(TRUE));

        verify(gapHandler).onGap(TERM_ID, termBuffer, 0, frameOffset);
    }

    @Test
    public void shouldReportSingleGapWhenBufferNotFull()
    {
        final int tail = FRAME_ALIGNMENT;
        final int highWaterMark = FRAME_ALIGNMENT * 3;

        when(termBuffer.getIntVolatile(lengthOffset(tail - FRAME_ALIGNMENT)))
            .thenReturn(FRAME_ALIGNMENT);
        when(termBuffer.getIntVolatile(lengthOffset(tail)))
            .thenReturn(0);
        when(termBuffer.getIntVolatile(lengthOffset(highWaterMark - FRAME_ALIGNMENT)))
            .thenReturn(FRAME_ALIGNMENT);

        assertThat(TermGapScanner.scanForGap(termBuffer, TERM_ID, tail, highWaterMark, gapHandler), is(TRUE));

        verify(gapHandler).onGap(TERM_ID, termBuffer, tail, FRAME_ALIGNMENT);
    }

    @Test
    public void shouldReportSingleGapWhenBufferIsFull()
    {
        final int tail = LOG_BUFFER_CAPACITY - (FRAME_ALIGNMENT * 2);
        final int highWaterMark = LOG_BUFFER_CAPACITY;

        when(termBuffer.getIntVolatile(lengthOffset(tail - FRAME_ALIGNMENT)))
            .thenReturn(FRAME_ALIGNMENT);
        when(termBuffer.getIntVolatile(lengthOffset(tail)))
            .thenReturn(0);
        when(termBuffer.getIntVolatile(lengthOffset(highWaterMark - FRAME_ALIGNMENT)))
            .thenReturn(FRAME_ALIGNMENT);

        assertThat(TermGapScanner.scanForGap(termBuffer, TERM_ID, tail, highWaterMark, gapHandler), is(TRUE));

        verify(gapHandler).onGap(TERM_ID, termBuffer, tail, FRAME_ALIGNMENT);
    }
}
