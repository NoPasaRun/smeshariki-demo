package smoke;

import java.util.function.Consumer;

import net.minecraft.client.Minecraft;

public record Case(String name, Consumer<Minecraft> open) {
}
