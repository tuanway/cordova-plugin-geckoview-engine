package java.beans;

public final class Introspector {
    private static final BeanInfo EMPTY = new BeanInfo() {
        @Override
        public PropertyDescriptor[] getPropertyDescriptors() {
            return new PropertyDescriptor[0];
        }
    };

    private Introspector() { }

    public static BeanInfo getBeanInfo(Class<?> beanClass) throws IntrospectionException {
        return EMPTY;
    }
}
