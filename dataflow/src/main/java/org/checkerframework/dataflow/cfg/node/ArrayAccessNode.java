package org.checkerframework.dataflow.cfg.node;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.javacutil.TreeUtils;

/**
 * A node for an array access:
 *
 * <pre>
 *   <em>arrayref</em> [ <em>index</em> ]
 * </pre>
 *
 * We allow array accesses without corresponding AST {@link Tree}s.
 */
public class ArrayAccessNode extends Node {

  /** The corresponding ArrayAccessTree. */
  protected final ArrayAccessTree tree;

  /** The array expression being accessed. */
  protected final Node array;

  /** The index expresssion used to access the array. */
  protected final Node index;

  /**
   * If this ArrayAccessNode is a node for an array desugared from an enhanced for loop, then the
   * {@code arrayExpression} field is the expression in the for loop, e.g., {@code arr} in {@code
   * for(Object o: arr}.
   *
   * <p>Is set by {@link #setArrayExpression}.
   */
  protected @Nullable ExpressionTree arrayExpression;

  /**
   * If this ArrayAccessNode is a node for an array access desugared from an enhanced for loop, then
   * the {@code enhancedForLoop} field is the {@code EnhancedForLoopTree} AST node.
   *
   * <p>Is set by {@link #setEnhancedForLoop}.
   */
  protected @Nullable EnhancedForLoopTree enhancedForLoop;

  /**
   * Create an ArrayAccessNode.
   *
   * @param t tree for the array access
   * @param array the node for the array expression being accessed
   * @param index the node for the index used to access the array
   */
  public ArrayAccessNode(ArrayAccessTree t, Node array, Node index) {
    super(TreeUtils.typeOf(t));
    this.tree = t;
    this.array = array;
    this.index = index;
  }

  /**
   * If this ArrayAccessNode is a node for an array desugared from an enhanced for loop, then return
   * the expression in the for loop, e.g., {@code arr} in {@code for(Object o: arr}. Otherwise,
   * return null.
   *
   * @return the array expression, or null if this is not an array desugared from an enhanced for
   *     loop
   */
  public @Nullable ExpressionTree getArrayExpression() {
    return arrayExpression;
  }

  /**
   * Set the array expression from a for loop.
   *
   * @param arrayExpression array expression
   * @see #getArrayExpression()
   */
  public void setArrayExpression(@Nullable ExpressionTree arrayExpression) {
    this.arrayExpression = arrayExpression;
  }

  /**
   * If this ArrayAccessNode is a node for an array access desugared from an enhanced for loop, then
   * return the {@code EnhancedForLoopTree} AST node. Otherwise, return null.
   *
   * @return the {@code EnhancedForLoopTree}, or null if this is not an array access desugared from
   *     an enhanced for loop
   */
  public @Nullable EnhancedForLoopTree getEnhancedForLoop() {
    return enhancedForLoop;
  }

  /**
   * Set the enhanced for loop from which {@code this} is desugared from.
   *
   * @param enhancedForLoop the {@code EnhancedForLoopTree}
   * @see #getEnhancedForLoop()
   */
  public void setEnhancedForLoop(@Nullable EnhancedForLoopTree enhancedForLoop) {
    this.enhancedForLoop = enhancedForLoop;
  }

  /**
   * Returns the node that represents the array expression being accessed.
   *
   * @return the array expression node
   */
  public Node getArray() {
    return array;
  }

  public Node getIndex() {
    return index;
  }

  @Override
  public ArrayAccessTree getTree() {
    return tree;
  }

  @Override
  public <R, P> R accept(NodeVisitor<R, P> visitor, P p) {
    return visitor.visitArrayAccess(this, p);
  }

  @Override
  public String toString() {
    String base = getArray().toString() + "[" + getIndex() + "]";
    return base;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof ArrayAccessNode)) {
      return false;
    }
    ArrayAccessNode other = (ArrayAccessNode) obj;
    return getArray().equals(other.getArray()) && getIndex().equals(other.getIndex());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getArray(), getIndex());
  }

  @Override
  @SideEffectFree
  public Collection<Node> getOperands() {
    return Arrays.asList(getArray(), getIndex());
  }
}
