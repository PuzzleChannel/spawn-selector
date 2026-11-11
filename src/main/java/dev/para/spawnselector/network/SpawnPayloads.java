package dev.para.spawnselector.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class SpawnPayloads {
    private SpawnPayloads() {}
    public record OpenMenu(String json) implements CustomPacketPayload {
        public static final Type<OpenMenu> TYPE = new Type<>(Identifier.fromNamespaceAndPath("spawnselector", "open_menu"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenMenu> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, OpenMenu::json, OpenMenu::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record TeamsUpdate(String json) implements CustomPacketPayload {
        public static final Type<TeamsUpdate> TYPE = new Type<>(Identifier.fromNamespaceAndPath("spawnselector", "teams_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TeamsUpdate> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, TeamsUpdate::json, TeamsUpdate::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Choose(int index) implements CustomPacketPayload {
        public static final Type<Choose> TYPE = new Type<>(Identifier.fromNamespaceAndPath("spawnselector", "choose"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Choose> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, Choose::index, Choose::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record TerritoryData(String json) implements CustomPacketPayload {
        public static final Type<TerritoryData> TYPE = new Type<>(Identifier.fromNamespaceAndPath("spawnselector", "territory_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, TerritoryData> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, TerritoryData::json, TerritoryData::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record CloseMenu() implements CustomPacketPayload {
        public static final Type<CloseMenu> TYPE = new Type<>(Identifier.fromNamespaceAndPath("spawnselector", "close_menu"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CloseMenu> CODEC = StreamCodec.unit(new CloseMenu());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}