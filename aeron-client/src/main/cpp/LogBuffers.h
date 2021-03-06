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

#ifndef INCLUDED_AERON_LOG_BUFFERS__
#define INCLUDED_AERON_LOG_BUFFERS__

#include <memory>
#include <vector>

#include <util/MemoryMappedFile.h>
#include <concurrent/logbuffer/LogBufferDescriptor.h>

namespace aeron {

using namespace aeron::common::util;
using namespace aeron::common::concurrent;
using namespace aeron::common::concurrent::logbuffer;

class LogBuffers
{
public:
    LogBuffers(const char *filename);

    virtual ~LogBuffers();

    inline AtomicBuffer& atomicBuffer(int index)
    {
        return m_buffers[index];
    }

private:
    std::vector<MemoryMappedFile::ptr_t> m_memoryMappedFiles;
    AtomicBuffer m_buffers[(2 * LogBufferDescriptor::PARTITION_COUNT) + 1];
};

}

#endif
