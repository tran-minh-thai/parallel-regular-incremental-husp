package common;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Pattern {
    public List<List<Integer>> elements = new ArrayList<>();

    public Pattern() {}

    public Pattern(int initialItem) {
        List<Integer> firstElement = new ArrayList<>();
        firstElement.add(initialItem);
        elements.add(firstElement);
    }

    public Pattern clonePattern() {
        Pattern p = new Pattern();
        for (List<Integer> itemset : this.elements) {
            p.elements.add(new ArrayList<>(itemset));
        }
        return p;
    }

    public void addIExtension(int item) {
        if (elements.isEmpty()) elements.add(new ArrayList<>());
        elements.get(elements.size() - 1).add(item);
    }

    public void addSExtension(int item) {
        List<Integer> newItemset = new ArrayList<>();
        newItemset.add(item);
        elements.add(newItemset);
    }

    public void removeLastIExtension() {
        List<Integer> lastItemset = elements.get(elements.size() - 1);
        lastItemset.remove(lastItemset.size() - 1);
    }

    public void removeLastSExtension() {
        elements.remove(elements.size() - 1);
    }

    // Concatenation helpers (used by USpan and HUSP-ULL)
    public Pattern iConcatenate(int item) {
        Pattern newPattern = clonePattern();
        newPattern.addIExtension(item);
        return newPattern;
    }

    public Pattern sConcatenate(int item) {
        Pattern newPattern = clonePattern();
        newPattern.addSExtension(item);
        return newPattern;
    }

    // Convert to a human-readable format for file output
    public String toIntuitiveString() {
        StringBuilder sb = new StringBuilder();
        for (List<Integer> element : elements) {
            if (element.size() > 1) {
                sb.append("(");
                for (int i = 0; i < element.size(); i++) {
                    sb.append(element.get(i));
                    if (i < element.size() - 1) sb.append(" ");
                }
                sb.append(") ");
            } else {
                sb.append(element.get(0)).append(" ");
            }
        }
        return sb.toString().trim();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Pattern pattern = (Pattern) o;
        return Objects.equals(elements, pattern.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(elements);
    }
}