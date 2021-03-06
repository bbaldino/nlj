/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi_modified.impl.neomedia.codec.video.vp8;

import org.jitsi.impl.neomedia.codec.video.vp8.*;

import java.nio.*;

/**
 * @author bb
 */
public class VP8Utils
{
    public static boolean isKeyFrame(ByteBuffer buf)
    {
        // Check if this is the start of a VP8 partition in the payload
        // descriptor.
        if (!DePacketizer.VP8PayloadDescriptor.isValid(buf.array(), buf.arrayOffset(), buf.limit()))
        {
            return false;
        }

        if (!DePacketizer.VP8PayloadDescriptor.isStartOfFrame(buf.array(), buf.arrayOffset()))
        {
            return false;
        }

        int szVP8PayloadDescriptor = DePacketizer.VP8PayloadDescriptor.getSize(buf.array(), buf.arrayOffset(), buf.limit());

        return DePacketizer.VP8PayloadHeader.isKeyFrame(
                buf.array(), buf.arrayOffset() + szVP8PayloadDescriptor);
    }

    public static boolean isStartOfFrame(ByteBuffer buf)
    {
        return DePacketizer.VP8PayloadDescriptor.isStartOfFrame(buf.array(), buf.arrayOffset());
    }
}
