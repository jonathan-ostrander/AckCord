/*
 * This file is part of AkkaCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.katsstuff.akkacord.data

import java.time.Instant

import net.katsstuff.akkacord.CacheSnapshotLike
import net.katsstuff.akkacord.CacheSnapshotLike.BotUser

import shapeless.tag._

case class CacheSnapshot(
    botUser: User @@ BotUser,
    dmChannels: Map[ChannelId, DMChannel],
    groupDmChannels: Map[ChannelId, GroupDMChannel],
    unavailableGuilds: Map[GuildId, UnavailableGuild],
    guilds: Map[GuildId, Guild],
    messages: Map[ChannelId, Map[MessageId, Message]],
    lastTyped: Map[ChannelId, Map[UserId, Instant]],
    users: Map[UserId, User],
    presences: Map[GuildId, Map[UserId, Presence]]
) extends CacheSnapshotLike {

  override type MapType[A, B] = Map[A, B]

  override def getChannelMessages(channelId: ChannelId): Map[MessageId, Message] =
    messages.getOrElse(channelId, Map.empty)

  override def getChannelLastTyped(channelId: ChannelId): Map[UserId, Instant] =
    lastTyped.getOrElse(channelId, Map.empty)
}
