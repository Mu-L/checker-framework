package org.checkerframework.checker.interning;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.checker.interning.qual.FindDistinct;
import org.checkerframework.checker.interning.qual.InternMethod;
import org.checkerframework.checker.interning.qual.Interned;
import org.checkerframework.checker.interning.qual.InternedDistinct;
import org.checkerframework.checker.interning.qual.PolyInterned;
import org.checkerframework.checker.interning.qual.UnknownInterned;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedPrimitiveType;
import org.checkerframework.framework.type.treeannotator.ListTreeAnnotator;
import org.checkerframework.framework.type.treeannotator.TreeAnnotator;
import org.checkerframework.framework.type.typeannotator.DefaultQualifierForUseTypeAnnotator;
import org.checkerframework.framework.type.typeannotator.ListTypeAnnotator;
import org.checkerframework.framework.type.typeannotator.TypeAnnotator;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationMirrorSet;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreeUtils;

/**
 * An {@link AnnotatedTypeFactory} that accounts for the properties of the Interned type system.
 * This type factory will add the {@link Interned} annotation to a type if the input:
 *
 * <ol>
 *   <li value="1">is a String literal
 *   <li value="2">is a class literal
 *   <li value="3">has an enum type
 *   <li value="4">has a primitive type
 *   <li value="5">has the type java.lang.Class
 *   <li value="6">is a use of a class declared to be @Interned
 * </ol>
 *
 * This type factory adds {@link InternedDistinct} to formal parameters that have a {@code @}{@link
 * FindDistinct} declaration annotation. (TODO: That isn't a good implementation, because it is not
 * accurate: the value might be equals() to some other Java value. More seriously, it permits too
 * much. Writing {@code @FindDistinct} should permit equality tests on the given formal parameter,
 * but should not (for example) permit the formal parameter to be assigned into an
 * {@code @InternedDistinct} location.)
 *
 * <p>This factory extends {@link BaseAnnotatedTypeFactory} and inherits its functionality,
 * including: flow-sensitive qualifier inference, qualifier polymorphism (of {@link PolyInterned}),
 * implicit annotations via {@link org.checkerframework.framework.qual.DefaultFor} on {@link
 * Interned} (to handle cases 1, 2, 4), and user-specified defaults via {@link DefaultQualifier}.
 * Case 5 is handled by the stub library.
 */
public class InterningAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

  /** The {@link UnknownInterned} annotation. */
  final AnnotationMirror TOP = AnnotationBuilder.fromClass(elements, UnknownInterned.class);

  /** The {@link Interned} annotation. */
  final AnnotationMirror INTERNED = AnnotationBuilder.fromClass(elements, Interned.class);

  /** The {@link InternedDistinct} annotation. */
  final AnnotationMirror INTERNED_DISTINCT =
      AnnotationBuilder.fromClass(elements, InternedDistinct.class);

  /** A set containing just {@link #INTERNED}. */
  final AnnotationMirrorSet INTERNED_SET = AnnotationMirrorSet.singleton(INTERNED);

  /**
   * Creates a new {@link InterningAnnotatedTypeFactory}.
   *
   * @param checker the checker to use
   */
  @SuppressWarnings("this-escape")
  public InterningAnnotatedTypeFactory(BaseTypeChecker checker) {
    super(checker);

    // If you update the following, also update ../../../../../docs/manual/interning-checker.tex
    addAliasedTypeAnnotation("com.sun.istack.internal.Interned", INTERNED);

    this.postInit();
  }

  @Override
  protected DefaultQualifierForUseTypeAnnotator createDefaultForUseTypeAnnotator() {
    return new InterningDefaultQualifierForUseTypeAnnotator(this);
  }

  /**
   * Does not add defaults for type uses on constructor results. Constructor results should be
   * {@code @UnknownInterned} by default.
   */
  static class InterningDefaultQualifierForUseTypeAnnotator
      extends DefaultQualifierForUseTypeAnnotator {

    public InterningDefaultQualifierForUseTypeAnnotator(AnnotatedTypeFactory typeFactory) {
      super(typeFactory);
    }

    @Override
    public Void visitExecutable(AnnotatedExecutableType type, Void p) {
      MethodSymbol methodElt = (MethodSymbol) type.getElement();

      if (methodElt == null || methodElt.getKind() != ElementKind.CONSTRUCTOR) {
        // Annotate method returns, not constructors.
        scan(type.getReturnType(), p);
      }
      AnnotatedTypeMirror receiverType = type.getReceiverType();
      if (receiverType != null
          // Intern method may be called on UnknownInterned object, so its receiver should
          // not be annotated as @Interned.
          && atypeFactory.getDeclAnnotation(methodElt, InternMethod.class) == null) {
        scanAndReduce(receiverType, p, null);
      }
      scanAndReduce(type.getParameterTypes(), p, null);
      scanAndReduce(type.getThrownTypes(), p, null);
      scanAndReduce(type.getTypeVariables(), p, null);
      return null;
    }
  }

  @Override
  public AnnotationMirrorSet getTypeDeclarationBounds(TypeMirror typeMirror) {
    if (typeMirror.getKind() == TypeKind.DECLARED
        && ((DeclaredType) typeMirror).asElement().getKind() == ElementKind.ENUM) {
      return INTERNED_SET;
    }
    return super.getTypeDeclarationBounds(typeMirror);
  }

  @Override
  protected TreeAnnotator createTreeAnnotator() {
    return new ListTreeAnnotator(super.createTreeAnnotator(), new InterningTreeAnnotator(this));
  }

  @Override
  protected TypeAnnotator createTypeAnnotator() {
    return new ListTypeAnnotator(new InterningTypeAnnotator(this), super.createTypeAnnotator());
  }

  @Override
  public void addComputedTypeAnnotations(Tree tree, AnnotatedTypeMirror type, boolean useFlow) {
    Element element = TreeUtils.elementFromTree(tree);
    if (!type.hasPrimaryAnnotationInHierarchy(INTERNED)
        && ElementUtils.isCompileTimeConstant(element)) {
      type.addAnnotation(INTERNED);
    }
    super.addComputedTypeAnnotations(tree, type, useFlow);
  }

  @Override
  public void addComputedTypeAnnotationsForWarnRedundant(
      Tree tree, AnnotatedTypeMirror type, boolean useFlow) {
    // Compared to `addComputedTypeAnnotations()`,
    // does not check whether the element is a compile-time constant.
    super.addComputedTypeAnnotations(tree, type, useFlow);
  }

  @Override
  public void addComputedTypeAnnotations(Element element, AnnotatedTypeMirror type) {
    if (!type.hasPrimaryAnnotationInHierarchy(INTERNED)
        && ElementUtils.isCompileTimeConstant(element)) {
      type.addAnnotation(INTERNED);
    }
    super.addComputedTypeAnnotations(element, type);
  }

  /** A class for adding annotations based on tree. */
  private class InterningTreeAnnotator extends TreeAnnotator {

    InterningTreeAnnotator(InterningAnnotatedTypeFactory atypeFactory) {
      super(atypeFactory);
    }

    @Override
    public Void visitBinary(BinaryTree tree, AnnotatedTypeMirror type) {
      if (TreeUtils.isCompileTimeString(tree)) {
        type.replaceAnnotation(INTERNED);
      } else if (TreeUtils.isStringConcatenation(tree)) {
        type.replaceAnnotation(TOP);
      } else if (type.getKind().isPrimitive()
          || tree.getKind() == Tree.Kind.EQUAL_TO
          || tree.getKind() == Tree.Kind.NOT_EQUAL_TO) {
        type.replaceAnnotation(INTERNED);
      } else {
        type.replaceAnnotation(TOP);
      }
      return super.visitBinary(tree, type);
    }

    /* Compound assignments never result in an interned result.
     */
    @Override
    public Void visitCompoundAssignment(CompoundAssignmentTree tree, AnnotatedTypeMirror type) {
      type.replaceAnnotation(TOP);
      return super.visitCompoundAssignment(tree, type);
    }

    @Override
    public Void visitTypeCast(TypeCastTree tree, AnnotatedTypeMirror type) {
      if (TreeUtils.typeOf(tree.getType()).getKind().isPrimitive()) {
        type.replaceAnnotation(INTERNED);
      }
      return super.visitTypeCast(tree, type);
    }

    @Override
    public Void visitIdentifier(IdentifierTree tree, AnnotatedTypeMirror type) {
      Element e = TreeUtils.elementFromUse(tree);
      if (atypeFactory.getDeclAnnotation(e, FindDistinct.class) != null) {
        // TODO: See note above about this being a poor implementation.
        type.replaceAnnotation(INTERNED_DISTINCT);
      }
      return super.visitIdentifier(tree, type);
    }
  }

  /** Adds @Interned to enum types. */
  private class InterningTypeAnnotator extends TypeAnnotator {

    InterningTypeAnnotator(InterningAnnotatedTypeFactory atypeFactory) {
      super(atypeFactory);
    }

    @Override
    public Void visitDeclared(AnnotatedDeclaredType t, Void p) {
      // case 3: Enum types, and the Enum class itself, are interned
      Element elt = t.getUnderlyingType().asElement();
      assert elt != null;
      if (elt.getKind() == ElementKind.ENUM) {
        t.replaceAnnotation(INTERNED);
      }
      return super.visitDeclared(t, p);
    }

    @Override
    public Void visitPrimitive(AnnotatedPrimitiveType t, Void p) {
      // case 4: primitive types are interned
      t.replaceAnnotation(INTERNED);
      return super.visitPrimitive(t, p);
    }
  }

  /**
   * Unbox type and replace any interning type annotations with @Interned since all primitives can
   * safely use ==. See case 4 in the class comments.
   *
   * <p>{@inheritDoc}
   */
  @Override
  public AnnotatedPrimitiveType getUnboxedType(AnnotatedDeclaredType type) {
    AnnotatedPrimitiveType primitive = super.getUnboxedType(type);
    primitive.replaceAnnotation(INTERNED);
    return primitive;
  }
}
