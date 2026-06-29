package algorithms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prefix enumeration tree storing the state of patterns that are currently high-utility or buffered
 * — proposedVN.tex §Buffer and pattern tree.
 * <p>
 * Each {@link Node} stores a VUL (vertical utility list) plus two child tables: within-itemset join
 * ({@link Node#iChildren}) and sequence-order join ({@link Node#sChildren}). Both tables are allocated
 * lazily (null until the first child): most nodes are leaves, so this saves significant memory on a
 * tree of millions of nodes. Uses a plain {@link HashMap} (not ConcurrentHashMap): the parallel
 * strategy partitions by root branch, each branch owned by one thread and traversed sequentially, so
 * there is no structural contention (see AlgoPRIncHUSP.staticBuild/maintain).
 */
public class PatternTree {

    public static final int I_EXT = 0;   // join within the same itemset
    public static final int S_EXT = 1;   // join in sequence order

    public static final class Node {
        public final int item;
        public final int extType;        // I_EXT or S_EXT (join type from parent)
        public Node parent;              // parent pointer -> build pattern sequence in O(depth)

        // lazily allocated child tables — null means no child of that type yet (leaf node: both null)
        public Map<Integer, Node> iChildren;
        public Map<Integer, Node> sChildren;

        /** VUL of the pattern at this node — maintained persistently across batches (extended only with new sequences). */
        public VerticalUtilityList vul;

        Node(int item, int extType) { this.item = item; this.extType = extType; }

        public boolean hasChildren() {
            return (sChildren != null && !sChildren.isEmpty()) || (iChildren != null && !iChildren.isEmpty());
        }
    }

    public final Node root = new Node(-1, S_EXT);

    /**
     * Get (or create if absent) the node for the extended pattern {@code parent ⊕ (extType,item)}.
     * Not synchronized: only the thread owning the branch calls it (root nodes created in the
     * sequential phase) -> lock-free and safe.
     */
    public Node getOrCreateNode(Node parent, int extType, int item) {
        Map<Integer, Node> children;
        if (extType == I_EXT) {
            if (parent.iChildren == null) parent.iChildren = new HashMap<>(4);
            children = parent.iChildren;
        } else {
            if (parent.sChildren == null) parent.sChildren = new HashMap<>(4);
            children = parent.sChildren;
        }
        Node child = children.get(item);
        if (child == null) { child = new Node(item, extType); child.parent = parent; children.put(item, child); }
        return child;
    }

    /** Find a node by path (sequence of extType/item pairs); null if it does not exist. */
    public Node find(List<int[]> path) {
        Node cur = root;
        for (int[] step : path) {           // step = {extType, item}
            Map<Integer, Node> children = (step[0] == I_EXT) ? cur.iChildren : cur.sChildren;
            if (children == null) return null;
            cur = children.get(step[1]);
            if (cur == null) return null;
        }
        return cur;
    }

    /** Enumerate all nodes (DFS) — used during end-of-batch result classification. */
    public List<Node> collectAllNodes() {
        List<Node> out = new ArrayList<>();
        dfs(root, out);
        return out;
    }

    private void dfs(Node n, List<Node> out) {
        if (n.sChildren != null) for (Node c : n.sChildren.values()) { out.add(c); dfs(c, out); }
        if (n.iChildren != null) for (Node c : n.iChildren.values()) { out.add(c); dfs(c, out); }
    }
}
