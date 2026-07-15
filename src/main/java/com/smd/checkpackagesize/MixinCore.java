package com.smd.checkpackagesize;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("TooManyTinkersCore")
public class MixinCore implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override
    public @Nullable String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public @Nullable String getModContainerClass() {
        return null;
    }

    @Override
    public @Nullable String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> map) {

    }

    @Override
    public @Nullable String getAccessTransformerClass() {
        return null;
    }

    @Override
    public List<String> getMixinConfigs() {
        return List.of(
                "checkpackagesize.default.mixin.json"
        );
    }
}
