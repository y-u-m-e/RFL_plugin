package sh.yumekui.toolkit.model;

import net.runelite.api.DynamicObject;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Renderable;

/**
 * Finds the {@link Model} the client draws for a scene object's {@link Renderable}, the way the GPU
 * plugin does: a static Model renderable as it is, a still {@link DynamicObject}'s
 * {@link DynamicObject#getModelZbuf()} (its static model; its built model as the fallback), an
 * animating one's current frame from {@link Renderable#getModel()} (zbuf as the fallback),
 * anything else's {@link Renderable#getModel()}, and an unlit {@link ModelData} lit.
 *
 * <p>{@link #path()} says which way the last model was found, even when that read threw, for
 * diagnostics. Client thread only.
 */
public final class RenderableModels
{
    /** How a model was found, in the order the close summary lists them. */
    public enum Path
    {
        MODEL("model"),
        ZBUF("zbuf"),
        DYNAMIC("dynamic"),
        MODEL_DATA("modelData"),
        GET_MODEL("getModel");

        /** The name in the close summary log. */
        public final String label;

        Path(String label)
        {
            this.label = label;
        }
    }

    private Path path = Path.GET_MODEL;

    /** The way the last {@link #resolve} found (or was trying to find) its model. */
    public Path path()
    {
        return path;
    }

    /** The model the client draws for {@code renderable}, or null when there is none. */
    public Model resolve(Renderable renderable)
    {
        path = Path.GET_MODEL;
        if (renderable instanceof Model)
        {
            path = Path.MODEL;
            return (Model) renderable;
        }
        if (renderable instanceof DynamicObject)
        {
            return dynamic((DynamicObject) renderable);
        }
        Model model = renderable.getModel();
        if (model == null && renderable instanceof ModelData)
        {
            path = Path.MODEL_DATA;
            model = ((ModelData) renderable).light();
        }
        return model;
    }

    /** A still object's static model first; an animating one's current frame first. */
    private Model dynamic(DynamicObject object)
    {
        boolean still = object.getAnimation() == null;
        path = still ? Path.ZBUF : Path.DYNAMIC;
        Model model = still ? object.getModelZbuf() : object.getModel();
        if (model == null)
        {
            path = still ? Path.DYNAMIC : Path.ZBUF;
            model = still ? object.getModel() : object.getModelZbuf();
        }
        return model;
    }

    /** Whether the model has every array {@link #geometry} reads. */
    public static boolean hasArrays(Model model)
    {
        return model.getVerticesX() != null && model.getFaceIndices1() != null && model.getFaceColors1() != null
            && model.getFaceColors3() != null;
    }

    /** The model copied into {@link ModelCapture.Geometry}; check {@link #hasArrays} first. */
    public static ModelCapture.Geometry geometry(Model model)
    {
        return ModelCapture.capture(model.getVerticesX(), model.getVerticesY(), model.getVerticesZ(),
            model.getVerticesCount(), model.getFaceIndices1(), model.getFaceIndices2(), model.getFaceIndices3(),
            model.getFaceCount(), model.getFaceColors1(), model.getFaceColors3(), model.getFaceTransparencies(),
            model.getFaceTextures());
    }
}
