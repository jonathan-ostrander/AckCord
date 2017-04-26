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
package net.katsstuff.akkacord.http.websocket

import java.time.OffsetDateTime

import akka.NotUsed
import akka.event.LoggingAdapter
import net.katsstuff.akkacord.APIMessage
import net.katsstuff.akkacord.data.{Attachment, Author, CacheSnapshot, GuildEmoji, Reaction, ReceivedEmbed, Role, Snowflake, User, VoiceState}
import net.katsstuff.akkacord.handlers.{CacheHandler, CacheSnapshotBuilder, PresenceUpdateHandler, RawHandlers, ReadyHandler}
import net.katsstuff.akkacord.http._
import shapeless._
import shapeless.labelled.FieldType

sealed trait WsMessage[D] {
  def op: OpCode
  def d:  D
  def s: Option[Int]        = None
  def t: Option[WsEvent[D]] = None
}

case class Dispatch[Data](sequence: Int, event: WsEvent[Data], d: Data) extends WsMessage[Data] {
  override val s:  Some[Int]           = Some(sequence)
  override val t:  Some[WsEvent[Data]] = Some(event)
  override def op: OpCode              = OpCode.Dispatch
}

case class Heartbeat(d: Option[Int]) extends WsMessage[Option[Int]] {
  override def op: OpCode = OpCode.Heartbeat
}

case class IdentifyObject(token: String, properties: Map[String, String], compress: Boolean, largeThreshold: Int, shard: Seq[Int])
object IdentifyObject {
  def createProperties: Map[String, String] =
    Map("$os" -> "linux", "$browser" -> "AkkaCord", "$device" -> "AkkaCord", "$referrer" -> "", "$referring_domain" -> "")
}
case class Identify(d: IdentifyObject) extends WsMessage[IdentifyObject] {
  override def op = OpCode.Identify
}

case class Game(name:            String)
case class StatusData(idleSince: Option[Int], game: Option[Game])
case class StatusUpdate(d:       StatusData) extends WsMessage[StatusData] {
  override def op: OpCode = OpCode.StatusUpdate
}

case class VoiceStatusData(guildId: Snowflake, channelId: Snowflake, selfMute: Boolean, selfDeaf: Boolean)
case class VoiceStateUpdate(d:      VoiceStatusData) extends WsMessage[VoiceStatusData] {
  override def op: OpCode = OpCode.VoiceStateUpdate
}

//Is it serverUpdate or ping?
case class VoiceServerUpdateData(token: String, guildId: Snowflake, endpoint: String)
case class VoiceServerUpdate(d:         VoiceServerUpdateData) extends WsMessage[VoiceServerUpdateData] {
  override def op: OpCode = OpCode.VoiceServerPing
}

case class ResumeData(token: String, sessionId: String, seq: Int)
case class Resume(d:         ResumeData) extends WsMessage[ResumeData] {
  override def op: OpCode = OpCode.Resume
}

case class Reconnect(d: NotUsed) extends WsMessage[NotUsed] {
  override def op: OpCode = OpCode.Reconnect
}

case class RequestGuildMembersData(guildId: Snowflake, query: String, limit: Int)
case class RequestGuildMembers(d:           RequestGuildMembersData) extends WsMessage[RequestGuildMembersData] {
  override def op: OpCode = OpCode.RequestGuildMembers
}

case class InvalidSession(d: NotUsed) extends WsMessage[NotUsed] {
  override def op: OpCode = OpCode.InvalidSession
}

case class HelloData(heartbeatInterval: Int, _trace: Seq[String])
case class Hello(d:                     HelloData) extends WsMessage[HelloData] {
  override def op: OpCode = OpCode.Hello
}

case class HeartbeatACK(d: NotUsed) extends WsMessage[NotUsed] {
  override def op: OpCode = OpCode.HeartbeatACK
}

sealed case class OpCode(code: Int)
object OpCode {
  object Dispatch            extends OpCode(0)
  object Heartbeat           extends OpCode(1)
  object Identify            extends OpCode(2)
  object StatusUpdate        extends OpCode(3)
  object VoiceStateUpdate    extends OpCode(4)
  object VoiceServerPing     extends OpCode(5)
  object Resume              extends OpCode(6)
  object Reconnect           extends OpCode(7)
  object RequestGuildMembers extends OpCode(8)
  object InvalidSession      extends OpCode(9)
  object Hello               extends OpCode(10)
  object HeartbeatACK        extends OpCode(11)

  def forCode(code: Int): Option[OpCode] = code match {
    case 0  => Some(Dispatch)
    case 1  => Some(Heartbeat)
    case 2  => Some(Identify)
    case 3  => Some(StatusUpdate)
    case 4  => Some(VoiceStateUpdate)
    case 5  => Some(VoiceServerPing)
    case 6  => Some(Resume)
    case 7  => Some(Reconnect)
    case 8  => Some(RequestGuildMembers)
    case 9  => Some(InvalidSession)
    case 10 => Some(Hello)
    case 11 => Some(HeartbeatACK)
    case _  => None
  }
}

sealed case class WsEvent[Data](
    name:        String,
    handler:     CacheHandler[Data],
    createEvent: Data => (CacheSnapshot, CacheSnapshot) => Option[APIMessage]
)
object WsEvent {
  case class ReadyData(
      v:               Int,
      user:            User,
      privateChannels: Seq[RawDMChannel],
      guilds:          Seq[RawUnavailableGuild],
      sessionId:       String,
      _trace:          Seq[String]
  )
  object Ready extends WsEvent[ReadyData]("READY", ReadyHandler, _ => (n, o) => Some(APIMessage.Ready(n, o)))

  case class ResumedData(_trace: Seq[String])

  private val resumeHandler = new CacheHandler[ResumedData] {
    override def handle(builder: CacheSnapshotBuilder, obj: ResumedData)(implicit log: LoggingAdapter): Unit = ()
  }

  private def notImplementedHandler[A] = new CacheHandler[A] {
    override def handle(builder: CacheSnapshotBuilder, obj: A)(implicit log: LoggingAdapter): Unit =
      log.warning(s"Not implemented handler for $obj")
  }

  private def notImplementedMessage[A]: A => (CacheSnapshot, CacheSnapshot) => Option[APIMessage] = _ => (_, _) => None

  object Resumed extends WsEvent[ResumedData]("RESUMED", resumeHandler, _ => (current, prev) => Some(APIMessage.Resumed(current, prev)))

  object ChannelCreate
      extends WsEvent[RawChannel](
        "CHANNEL_CREATE",
        RawHandlers.rawChannelUpdateHandler,
        data => (current, prev) => current.getChannel(data.id).map(c => APIMessage.ChannelCreate(c, current, prev))
      )

  object ChannelUpdate
      extends WsEvent[RawGuildChannel](
        "CHANNEL_UPDATE",
        RawHandlers.rawGuildChannelUpdateHandler,
        data => (current, prev) => current.getGuildChannel(data.id).map(c => APIMessage.ChannelUpdate(c, current, prev))
      )

  object ChannelDelete
      extends WsEvent[RawChannel](
        "CHANNEL_DELETE",
        RawHandlers.rawChannelDeleteHandler,
        data => (current, prev) => prev.getGuildChannel(data.id).map(c => APIMessage.ChannelDelete(c, current, prev))
      )

  object GuildCreate
      extends WsEvent[RawGuild](
        "GUILD_CREATE",
        RawHandlers.rawGuildUpdateHandler,
        data => (current, prev) => current.getGuild(data.id).map(g => APIMessage.GuildCreate(g, current, prev))
      )
  object GuildUpdate
      extends WsEvent[RawGuild](
        "GUILD_UPDATE",
        RawHandlers.rawGuildUpdateHandler,
        data => (current, prev) => current.getGuild(data.id).map(g => APIMessage.GuildUpdate(g, current, prev))
      )

  case class GuildDeleteData(id: Snowflake, unavailable: Boolean)
  object GuildDelete
      extends WsEvent[GuildDeleteData](
        "GUILD_DELETE",
        RawHandlers.deleteGuildDataHandler,
        data => (current, prev) => prev.getGuild(data.id).map(g => APIMessage.GuildDelete(g, data.unavailable, current, prev))
      )

  val userGen = LabelledGeneric[User]
  type GuildUser = FieldType[Witness.`'guildId`.T, Snowflake] :: userGen.Repr
  object GuildBanAdd    extends WsEvent[GuildUser]("GUILD_BAN_ADD", notImplementedHandler, notImplementedMessage)
  object GuildBanRemove extends WsEvent[GuildUser]("GUILD_BAN_REMOVE", notImplementedHandler, notImplementedMessage)

  case class GuildEmojisUpdateData(guildId: Snowflake, emojis: Seq[GuildEmoji])
  object GuildEmojisUpdate
      extends WsEvent[GuildEmojisUpdateData](
        "GUILD_EMOJIS_UPDATE",
        RawHandlers.guildEmojisUpdateDataHandler,
        data => (current, prev) => current.getGuild(data.guildId).map(g => APIMessage.GuildEmojiUpdate(g, data.emojis, current, prev))
      )

  case class GuildIntegrationsUpdateData(guildId: Snowflake)
  object GuildIntegrationsUpdate
      extends WsEvent[GuildIntegrationsUpdateData]("GUILD_INTEGRATIONS_UPDATE", notImplementedHandler, notImplementedMessage)

  val guildMemberGen = LabelledGeneric[RawGuildMember]
  type RawGuildMemberWithGuild = FieldType[Witness.`'guildId`.T, Snowflake] :: guildMemberGen.Repr
  object GuildMemberAdd
      extends WsEvent[RawGuildMemberWithGuild](
        "GUILD_MEMBER_ADD",
        RawHandlers.rawGuildMemberWithGuildUpdateHandler,
        data =>
          (current, prev) =>
            for {
              g   <- current.getGuild(data.head)
              mem <- g.members.get(guildMemberGen.from(data.tail).user.id)
            } yield APIMessage.GuildMemberAdd(mem, g, current, prev)
      )

  case class GuildMemberRemoveData(guildId: Snowflake, user: User)
  object GuildMemberRemove
      extends WsEvent[GuildMemberRemoveData](
        "GUILD_MEMBER_REMOVE",
        RawHandlers.rawGuildMemberDeleteHandler,
        data => (current, prev) => current.getGuild(data.guildId).map(g => APIMessage.GuildMemberRemove(data.user, g, current, prev))
      )

  case class GuildMemberUpdateData(guildId: Snowflake, roles: Seq[Snowflake], user: User, nick: Option[String]) //Nick can probably be null here
  object GuildMemberUpdate
      extends WsEvent[GuildMemberUpdateData](
        "GUILD_MEMBER_UPDATE",
        RawHandlers.rawGuildMemberUpdateHandler,
        data =>
          (current, prev) =>
            current
              .getGuild(data.guildId)
              .map(g => APIMessage.GuildMemberUpdate(g, data.roles.flatMap(current.getRole), data.user, data.nick, current, prev))
      )

  case class GuildMemberChunkData(guildId: Snowflake, members: Seq[RawGuildMember])
  object GuildMemberChunk
      extends WsEvent[GuildMemberChunkData](
        "GUILD_MEMBER_CHUNK",
        RawHandlers.rawGuildMemberChunkHandler,
        data =>
          (current, prev) =>
            current
              .getGuild(data.guildId)
              .map(g => APIMessage.GuildMembersChunk(g, data.members.flatMap(m => g.members.get(m.user.id)), current, prev))
      )

  case class GuildRoleModifyData(guildId: Snowflake, role: Role)
  object GuildRoleCreate
      extends WsEvent[GuildRoleModifyData](
        "GUILD_ROLE_CREATE",
        RawHandlers.roleUpdateHandler,
        data => (current, prev) => current.getGuild(data.guildId).map(g => APIMessage.GuildRoleCreate(g, data.role, current, prev))
      )
  object GuildRoleUpdate
      extends WsEvent[GuildRoleModifyData](
        "GUILD_ROLE_UPDATE",
        RawHandlers.roleUpdateHandler,
        data => (current, prev) => current.getGuild(data.guildId).map(g => APIMessage.GuildRoleUpdate(g, data.role, current, prev))
      )

  case class GuildRoleDeleteData(guildId: Snowflake, roleId: Snowflake)
  object GuildRoleDelete
      extends WsEvent[GuildRoleDeleteData](
        "GUILD_ROLE_DELETE",
        RawHandlers.roleDeleteHandler,
        data => (current, prev) => current.getGuild(data.guildId).map(g => APIMessage.GuildRoleDelete(g, data.roleId, current, prev))
      )

  object MessageCreate
      extends WsEvent[RawMessage](
        "MESSAGE_CREATE",
        RawHandlers.rawMessageUpdateHandler,
        data => (current, prev) => current.getMessage(data.id).map(message => APIMessage.MessageCreate(message, current, prev))
      )

  //RawPartialMessage is defined explicitly because we need to handle the author
  case class RawPartialMessage(
      id:              Snowflake,
      channelId:       Snowflake,
      author:          Option[Author],
      content:         Option[String],
      timestamp:       Option[OffsetDateTime],
      editedTimestamp: Option[OffsetDateTime],
      tts:             Option[Boolean],
      mentionEveryone: Option[Boolean],
      mentions:        Option[Seq[User]],
      mentionRoles:    Option[Seq[Snowflake]],
      attachment:      Option[Seq[Attachment]],
      embeds:          Option[Seq[ReceivedEmbed]],
      reactions:       Option[Seq[Reaction]],
      nonce:           Option[Snowflake],
      pinned:          Option[Boolean],
      webhookId:       Option[String]
  )
  object MessageUpdate
      extends WsEvent[RawPartialMessage](
        "MESSAGE_UPDATE",
        RawHandlers.rawPartialMessageUpdateHandler,
        data => (current, prev) => current.getMessage(data.id).map(message => APIMessage.MessageCreate(message, current, prev))
      )

  case class MessageDeleteData(id: Snowflake, channelId: Snowflake)
  object MessageDelete
      extends WsEvent[MessageDeleteData](
        "MESSAGE_DELETE",
        RawHandlers.rawMessageDeleteHandler,
        data =>
          (current, prev) =>
            for {
              message <- prev.getMessage(data.id)
              channel <- current.getChannel(data.channelId)
            } yield APIMessage.MessageDelete(message, channel, current, prev)
      )

  case class MessageDeleteBulkData(ids: Seq[Snowflake], channelId: Snowflake)
  object MessageDeleteBulk
      extends WsEvent[MessageDeleteBulkData](
        "MESSAGE_DELETE_BULK",
        RawHandlers.rawMessageDeleteBulkHandler,
        data =>
          (current, prev) =>
            current
              .getChannel(data.channelId)
              .map(channel => APIMessage.MessageDeleteBulk(data.ids.flatMap(prev.getMessage(_).toSeq), channel, current, prev))
      )

  case class PresenceUpdateData(
      user:    PartialUser,
      roles:   Seq[Snowflake],
      game:    Option[RawPresenceGame],
      guildId: Option[Snowflake],
      status:  Option[String]
  )
  object PresenceUpdate
      extends WsEvent[PresenceUpdateData](
        "PRESENCE_UPDATE",
        PresenceUpdateHandler,
        data =>
          (current, prev) =>
            data.guildId.flatMap { guildId =>
              current
                .getPresence(guildId, data.user.head)
                .map(presence => APIMessage.PresenceUpdate(presence, current, prev))
        }
      )

  case class TypingStartData(channelId: Snowflake, userId: Snowflake, timestamp: Int)
  object TypingStart extends WsEvent[TypingStartData]("TYPING_START", notImplementedHandler, notImplementedMessage)

  //object UserSettingsUpdate extends Event("USER_SETTINGS_UPDATE") //TODO

  object UserUpdate        extends WsEvent[User]("USER_UPDATE", notImplementedHandler, notImplementedMessage)
  object VoiceStateUpdate  extends WsEvent[VoiceState]("VOICE_STATUS_UPDATE", notImplementedHandler, notImplementedMessage)
  object VoiceServerUpdate extends WsEvent[VoiceServerUpdateData]("VOICE_SERVER_UPDATE", notImplementedHandler, notImplementedMessage)

  def forName(name: String): Option[WsEvent[_]] = name match {
    case "READY"                     => Some(Ready)
    case "RESUMED"                   => Some(Resumed)
    case "CHANNEL_CREATE"            => Some(ChannelCreate)
    case "CHANNEL_UPDATE"            => Some(ChannelUpdate)
    case "CHANNEL_DELETE"            => Some(ChannelDelete)
    case "GUILD_CREATE"              => Some(GuildCreate)
    case "GUILD_UPDATE"              => Some(GuildUpdate)
    case "GUILD_DELETE"              => Some(GuildDelete)
    case "GUILD_BAN_ADD"             => Some(GuildBanAdd)
    case "GUILD_BAN_REMOVE"          => Some(GuildBanRemove)
    case "GUILD_EMOJIS_UPDATE"       => Some(GuildEmojisUpdate)
    case "GUILD_INTEGRATIONS_UPDATE" => Some(GuildIntegrationsUpdate)
    case "GUILD_MEMBER_ADD"          => Some(GuildMemberAdd)
    case "GUILD_MEMBER_REMOVE"       => Some(GuildMemberRemove)
    case "GUILD_MEMBER_UPDATE"       => Some(GuildMemberUpdate)
    case "GUILD_MEMBER_CHUNK"        => Some(GuildMemberChunk)
    case "GUILD_ROLE_CREATE"         => Some(GuildRoleCreate)
    case "GUILD_ROLE_UPDATE"         => Some(GuildRoleUpdate)
    case "GUILD_ROLE_DELETE"         => Some(GuildRoleDelete)
    case "MESSAGE_CREATE"            => Some(MessageCreate)
    case "MESSAGE_UPDATE"            => Some(MessageUpdate)
    case "MESSAGE_DELETE"            => Some(MessageDelete)
    case "MESSAGE_DELETE_BULK"       => Some(MessageDeleteBulk)
    case "PRESENCE_UPDATE"           => Some(PresenceUpdate)
    case "TYPING_START"              => Some(TypingStart)
    case "USER_SETTINGS_UPDATE"      => ??? //Some(UserSettingsUpdate)
    case "USER_UPDATE"               => Some(UserUpdate)
    case "VOICE_STATE_UPDATE"        => Some(VoiceStateUpdate)
    case "VOICE_SERVER_UPDATE"       => Some(VoiceServerUpdate)
    case _                           => None
  }
}