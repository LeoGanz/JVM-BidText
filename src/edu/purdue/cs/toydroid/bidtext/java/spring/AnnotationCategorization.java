package edu.purdue.cs.toydroid.bidtext.java.spring;

import java.util.HashSet;
import java.util.Set;

public class AnnotationCategorization {

    private final Set<AnnotationCategory> categories = new HashSet<>();

    public void setController() {
        categories.add(AnnotationCategory.CONTROLLER);
    }

    public void setBean() {
        categories.add(AnnotationCategory.BEAN);
    }

    public void setPrototype() {
        categories.add(AnnotationCategory.PROTOTYPE);
    }

    public boolean isController() {
        return categories.contains(AnnotationCategory.CONTROLLER);
    }

    public boolean isBean() {
        return categories.contains(AnnotationCategory.BEAN);
    }

    public boolean isPrototype() {
        return categories.contains(AnnotationCategory.PROTOTYPE);
    }

    public boolean isNotEmpty() {
        return !categories.isEmpty();
    }

    @Override
    public String toString() {
        return "AnnotationCategorization{" +
                "categories=" + categories +
                '}';
    }

    private enum AnnotationCategory {
        CONTROLLER,
        BEAN,
        PROTOTYPE
    }
}
