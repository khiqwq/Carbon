package first.carbon.client;

import first.carbon.Carbon;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

// 使用原版控件的客户端配置界面，关闭时写回 carbon-client.toml。
public final class ConfigScreen extends Screen {
    private final Screen parent;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("carbon.configuration.title", "Carbon"));
        this.parent = parent;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void init() {
        int y = height / 6;
        for (Carbon.Value<?> value : Carbon.CONFIG.values) {
            Component name = Component.translatable(value.translation());
            Tooltip tooltip = Tooltip.create(Component.translatable(value.translation() + ".tooltip"));
            if (value.defaultValue instanceof Boolean) {
                Carbon.Value<Boolean> option = (Carbon.Value<Boolean>) value;
                CycleButton<Boolean> button = CycleButton.onOffBuilder(option.get()).create(width / 2 - 155, y, 310, 20, name, (cycle, enabled) -> option.set(enabled));
                button.setTooltip(tooltip);
                addRenderableWidget(button);
            } else {
                Carbon.Value<Double> option = (Carbon.Value<Double>) value;
                AbstractSliderButton slider = new AbstractSliderButton(width / 2 - 155, y, 310, 20, Component.empty(), option.get() / 20.0) {
                    {
                        updateMessage();
                    }

                    @Override
                    protected void updateMessage() {
                        setMessage(Component.translatable("options.generic_value", name, String.format("%.1f", Math.round(this.value * 200) / 10.0)));
                    }

                    @Override
                    protected void applyValue() {
                        option.set(Math.round(this.value * 200) / 10.0);
                    }
                };
                slider.setTooltip(tooltip);
                addRenderableWidget(slider);
            }
            y += 24;
        }
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).bounds(width / 2 - 100, y + 12, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 15, -1);
    }

    @Override
    public void onClose() {
        Carbon.CONFIG.save();
        minecraft.setScreen(parent);
    }
}
