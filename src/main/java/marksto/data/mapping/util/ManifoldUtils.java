package marksto.data.mapping.util;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import manifold.api.fs.IFile;
import manifold.api.fs.url.URLFileImpl;
import manifold.api.host.IRuntimeManifoldHost;
import manifold.api.json.*;
import manifold.api.json.schema.JsonEnumType;
import manifold.api.json.schema.JsonUnionType;
import manifold.ext.api.IBindingType;
import manifold.internal.host.RuntimeManifoldHost;
import manifold.json.extensions.java.net.URL.ManUrlExt;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * @author marksto
 */
public class ManifoldUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ManifoldUtils.class);

    private ManifoldUtils() {}

    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    public static <T> List<T> parseJsonObjectsList(String jsonStr, Class<T> clazz) {
        IJsonList<T> jsonList = IJsonList.<T>load().fromJson(jsonStr);
        return jsonList.getList();
    }

    // -------------------------------------------------------------------------

    public static boolean areEqual(IBindingType actualVal, IBindingType expectedVal) {
        return StringUtils.equals(
            (String) expectedVal.toBindingValue(),
            (String) actualVal.toBindingValue()
        );
    }

    // -------------------------------------------------------------------------

    // TODO: RE-IMPLEMENT to be able to work in Dynamic Mode, when there's no pre-compiled type JSON file.
    public static JsonStructureType retrieveTypeInformation(String typeClassName) {
        String resourcePath = typeClassName.replaceAll("\\.", "/") + ".json";
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

        URL url = classLoader.getResource(resourcePath);
        if (url == null) {
            return null;
        }

        Object jsonObj = ManUrlExt.getJsonContent(url);
        String objName = typeClassName.substring(typeClassName.lastIndexOf('.') + 1);
        IRuntimeManifoldHost host = RuntimeManifoldHost.get();
        IFile iFile = new URLFileImpl(host.getFileSystem(), url);
        return (JsonStructureType) Json
            .transformJsonObject(host, objName, iFile, null, jsonObj);
    }

    // -------------------------------------------------------------------------

    public static Class<?> getPropertyType(JsonStructureType jsonType, List<String> pathToProp) {
        IJsonType levelType = jsonType;

        for (String propName : pathToProp) {
            if (levelType instanceof JsonListType) {
                levelType = ((JsonListType) levelType).getComponentType();
            }

            if (levelType instanceof JsonStructureType) {
                Preconditions.checkState(!(levelType instanceof JsonEnumType),
                    "Enum type in a non-leaf property=" + propName);
                Preconditions.checkState(!(levelType instanceof JsonUnionType),
                    "Union type in a non-leaf property=" + propName);
                levelType = ((JsonStructureType) levelType).getMembers().get(propName);
            } else if (levelType instanceof DynamicType) {
                List<String> issues = levelType.getParent().getIssues().stream()
                    .map(JsonIssue::getMessage)
                    .collect(toList());
                LOG.warn("DynamicType is used as a type, property='{}', issues='[{}]'",
                    Joiner.on(',').join(pathToProp), Joiner.on(',').join(issues));
                return Object.class; // Dynamic simply means Object in a Java world
            } else {
                // NOTE: I know that 'JsonFormatType' will fall-out here, but I don't need it at all.
                LOG.error("Missing impl case '{}', property='{}'",
                    levelType.getClass().getName(), Joiner.on(',').join(pathToProp));
                throw new NotImplementedException("Missing impl case: " + levelType.getClass().getName());
            }
        }

        return getLeafPropertyType(levelType, pathToProp);
    }

    private static Class<?> getLeafPropertyType(IJsonType leafType, List<String> pathToProp) {
        if (leafType instanceof JsonListType) {
            leafType = ((JsonListType) leafType).getComponentType();
        }

        if (leafType instanceof JsonBasicType) {
            return convertBasicType((JsonBasicType) leafType);
        } else if (leafType instanceof JsonEnumType) {
            // TODO: Implement other types of Enums (non-String ones)?
            return String.class;
        } else if (leafType instanceof JsonUnionType) {
            return Object.class; // there is no concept of a union type in Java
        } else if (leafType instanceof DynamicType) {
            List<String> issues = leafType.getParent().getIssues().stream()
                .map(JsonIssue::getMessage)
                .collect(toList());
            LOG.warn("DynamicType is used as a type, property='{}', issues='[{}]'",
                Joiner.on(',').join(pathToProp), Joiner.on(',').join(issues));
            return Object.class; // Dynamic simply means Object in a Java world
        } else {
            LOG.error("Missing impl case '{}', property='{}'",
                leafType.getClass().getName(), Joiner.on(',').join(pathToProp));
            return null; // simply omitting, allowing it to be of 'String' type
        }
    }

    private static Class<?> convertBasicType(JsonBasicType basicType) {
        if (basicType.isPrimitive()) {
            return basicType.box();
        } else if ("String".equals(basicType.getName())) {
            return String.class;
        } else {
            return Void.class; // the 'null' value option
        }
    }

}
