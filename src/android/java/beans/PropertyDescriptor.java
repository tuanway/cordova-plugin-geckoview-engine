package java.beans;

import java.lang.reflect.Method;

public class PropertyDescriptor extends FeatureDescriptor {
    private final String name;
    private Method readMethod;
    private Method writeMethod;

    public PropertyDescriptor(String propertyName, Class<?> beanClass) {
        this.name = propertyName;
    }

    public PropertyDescriptor(String propertyName, Method readMethod, Method writeMethod) {
        this.name = propertyName;
        this.readMethod = readMethod;
        this.writeMethod = writeMethod;
    }

    public String getName() {
        return name;
    }

    public Method getReadMethod() {
        return readMethod;
    }

    public void setReadMethod(Method method) {
        this.readMethod = method;
    }

    public Method getWriteMethod() {
        return writeMethod;
    }

    public void setWriteMethod(Method method) {
        this.writeMethod = method;
    }
}
