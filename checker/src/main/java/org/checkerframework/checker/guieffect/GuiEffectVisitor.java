package org.checkerframework.checker.guieffect;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import org.checkerframework.checker.guieffect.qual.AlwaysSafe;
import org.checkerframework.checker.guieffect.qual.PolyUI;
import org.checkerframework.checker.guieffect.qual.PolyUIEffect;
import org.checkerframework.checker.guieffect.qual.PolyUIType;
import org.checkerframework.checker.guieffect.qual.SafeEffect;
import org.checkerframework.checker.guieffect.qual.UI;
import org.checkerframework.checker.guieffect.qual.UIEffect;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.type.AnnotatedTypeFactory.ParameterizedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationMirrorSet;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreePathUtil;
import org.checkerframework.javacutil.TreeUtils;
import org.checkerframework.javacutil.TypesUtils;

/** Require that only UI code invokes code with the UI effect. */
public class GuiEffectVisitor extends BaseTypeVisitor<GuiEffectTypeFactory> {
  /** The type of the class currently being visited. */
  private @Nullable AnnotatedDeclaredType classType = null;

  /** The receiver type of the enclosing method tree. */
  private @Nullable AnnotatedDeclaredType receiverType = null;

  /** If true, display debugging information. */
  protected final boolean debugSpew;

  // effStack and currentMethods should always be the same size.
  protected final ArrayDeque<Effect> effStack;
  protected final ArrayDeque<MethodTree> currentMethods;

  public GuiEffectVisitor(BaseTypeChecker checker) {
    super(checker);
    debugSpew = checker.getLintOption("debugSpew", false);
    if (debugSpew) {
      System.err.println("Running GuiEffectVisitor");
    }
    effStack = new ArrayDeque<>();
    currentMethods = new ArrayDeque<>();
  }

  @Override
  protected GuiEffectTypeFactory createTypeFactory() {
    return new GuiEffectTypeFactory(checker, debugSpew);
  }

  // The issue is that the receiver implicitly receives an @AlwaysSafe anno, so calls on @UI
  // references fail because the framework doesn't implicitly upcast the receiver (which in
  // general wouldn't be sound).
  // TODO: Fix method receiver defaults: method-polymorphic for any polymorphic method, UI
  //       for any UI instantiations, safe otherwise
  @Override
  protected void checkMethodInvocability(
      AnnotatedExecutableType method, MethodInvocationTree tree) {
    // The inherited version of this complains about invoking methods of @UI instantiations of
    // classes, which by default are annotated @AlwaysSafe, which for data type qualifiers is
    // reasonable, but it not what we want, since we want .
    // TODO: Undo this hack!
  }

  protected class GuiEffectOverrideChecker extends OverrideChecker {
    /**
     * Extend the receiver part of the method override check. We extend the standard check, to
     * additionally permit narrowing the receiver's permission to {@code @AlwaysSafe} in a safe
     * instantiation of a {@code @PolyUIType}. Returns true if the override is permitted.
     */
    @Override
    protected boolean checkReceiverOverride() {
      // We cannot reuse the inherited method because it directly issues the failure, but we
      // want a more permissive check.  So this is copied down and modified from
      // BaseTypeVisitor.OverrideChecker.checkReceiverOverride.
      // isSubtype() requires its arguments to be actual subtypes with
      // respect to JLS, but the overrider receiver is not a subtype of the
      // overridden receiver.  Hence copying the annotations.
      // TODO: Does this need to be improved for generic receivers?  I.e., do we need to
      // add extra checking to reject the case of also changing qualifiers in type parameters?
      // Such as overriding a {@code @PolyUI C<@UI T>} by {@code @AlwaysSafe C<@AlwaysSafe
      // T>}?  The change to the receiver permission is acceptable, while the change to the
      // parameter should be rejected.
      AnnotatedTypeMirror overriddenReceiver =
          overrider.getReceiverType().getErased().shallowCopy(false);
      overriddenReceiver.addAnnotations(overridden.getReceiverType().getPrimaryAnnotations());
      if (!atypeFactory
          .getTypeHierarchy()
          .isSubtype(overriddenReceiver, overrider.getReceiverType().getErased())) {
        // This is the point at which the default check would issue an error.
        // We additionally permit overrides to move from @PolyUI receivers to @AlwaysSafe
        // receivers, if it's in a @AlwaysSafe specialization of a @PolyUIType
        boolean safeParent = overriddenType.getPrimaryAnnotation(AlwaysSafe.class) != null;
        boolean polyParentDecl =
            atypeFactory.getDeclAnnotation(
                    overriddenType.getUnderlyingType().asElement(), PolyUIType.class)
                != null;
        // TODO: How much validation do I need here?  Do I need to check that the overridden
        // receiver was really @PolyUI and the method is really an @PolyUIEffect?  I don't
        // think so - we know it's a polymorphic parent type, so all receivers would be
        // @PolyUI.
        // Java would already reject before running type annotation processors if the Java
        // types were wrong.
        // The *only* extra leeway we want to permit is overriding @PolyUI receiver to
        // @AlwaysSafe.  But with generics, the tentative check below is inadequate.
        boolean safeReceiverOverride =
            overrider.getReceiverType().getPrimaryAnnotation(AlwaysSafe.class) != null;
        if (safeParent && polyParentDecl && safeReceiverOverride) {
          return true;
        }
        checker.reportError(
            overriderTree,
            "override.receiver",
            overrider.getReceiverType(),
            overridden.getReceiverType(),
            overriderType,
            overrider,
            overriddenType,
            overridden);
        return false;
      }
      return true;
    }

    /**
     * Create a GuiEffectOverrideChecker.
     *
     * @param overriderTree the AST node of the overriding method or method reference
     * @param overrider the type of the overriding method
     * @param overridingType the type enclosing the overrider method, usually an
     *     AnnotatedDeclaredType; for Method References may be something else
     * @param overridingReturnType the return type of the overriding method
     * @param overridden the type of the overridden method
     * @param overriddenType the declared type enclosing the overridden method
     * @param overriddenReturnType the return type of the overridden method
     */
    public GuiEffectOverrideChecker(
        Tree overriderTree,
        AnnotatedExecutableType overrider,
        AnnotatedTypeMirror overridingType,
        AnnotatedTypeMirror overridingReturnType,
        AnnotatedExecutableType overridden,
        AnnotatedDeclaredType overriddenType,
        AnnotatedTypeMirror overriddenReturnType) {
      super(
          overriderTree,
          overrider,
          overridingType,
          overridingReturnType,
          overridden,
          overriddenType,
          overriddenReturnType);
    }
  }

  @Override
  protected OverrideChecker createOverrideChecker(
      Tree overriderTree,
      AnnotatedExecutableType overrider,
      AnnotatedTypeMirror overridingType,
      AnnotatedTypeMirror overridingReturnType,
      AnnotatedExecutableType overridden,
      AnnotatedTypeMirror.AnnotatedDeclaredType overriddenType,
      AnnotatedTypeMirror overriddenReturnType) {
    return new GuiEffectOverrideChecker(
        overriderTree,
        overrider,
        overridingType,
        overridingReturnType,
        overridden,
        overriddenType,
        overriddenReturnType);
  }

  @Override
  protected AnnotationMirrorSet getExceptionParameterLowerBoundAnnotations() {
    return new AnnotationMirrorSet(AnnotationBuilder.fromClass(elements, AlwaysSafe.class));
  }

  @Override
  public boolean isValidUse(
      AnnotatedTypeMirror.AnnotatedDeclaredType declarationType,
      AnnotatedTypeMirror.AnnotatedDeclaredType useType,
      Tree tree) {
    boolean ret =
        useType.hasPrimaryAnnotation(AlwaysSafe.class)
            || useType.hasPrimaryAnnotation(PolyUI.class)
            || atypeFactory.isPolymorphicType(
                (TypeElement) declarationType.getUnderlyingType().asElement())
            || (useType.hasPrimaryAnnotation(UI.class)
                && declarationType.hasPrimaryAnnotation(UI.class));
    if (debugSpew && !ret) {
      System.err.println("use: " + useType);
      System.err.println("use safe: " + useType.hasPrimaryAnnotation(AlwaysSafe.class));
      System.err.println("use poly: " + useType.hasPrimaryAnnotation(PolyUI.class));
      System.err.println("use ui: " + useType.hasPrimaryAnnotation(UI.class));
      System.err.println(
          "declaration safe: " + declarationType.hasPrimaryAnnotation(AlwaysSafe.class));
      System.err.println(
          "declaration poly: "
              + atypeFactory.isPolymorphicType(
                  (TypeElement) declarationType.getUnderlyingType().asElement()));
      System.err.println("declaration ui: " + declarationType.hasPrimaryAnnotation(UI.class));
      System.err.println("declaration: " + declarationType);
    }
    return ret;
  }

  @Override
  @SuppressWarnings("interning:not.interned") // comparing AST nodes
  public Void visitLambdaExpression(LambdaExpressionTree tree, Void p) {
    Void v = super.visitLambdaExpression(tree, p);
    // If this is a lambda inferred to be @UI, scan up the path and re-check any assignments
    // involving it.
    if (atypeFactory.isDirectlyMarkedUIThroughInference(tree)) {
      // Backtrack path to the lambda expression itself
      TreePath path = getCurrentPath();
      while (path.getLeaf() != tree) {
        assert !(path.getLeaf() instanceof CompilationUnitTree);
        path = path.getParentPath();
      }
      scanUp(path);
    }
    return v;
  }

  @Override
  protected void checkExtendsAndImplements(ClassTree classTree) {
    // Skip this check
  }

  @Override
  protected void checkConstructorResult(
      AnnotatedExecutableType constructorType, ExecutableElement constructorElement) {
    // Skip this check.
  }

  @Override
  protected void warnInvalidPolymorphicQualifier(ClassTree classTree) {
    // Polymorphic qualifiers are legal on classes, so skip this check.
  }

  // Check that the invoked effect is <= permitted effect (effStack.peek())
  @Override
  public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
    if (debugSpew) {
      System.err.println("For invocation " + tree + " in " + currentMethods.peek().getName());
    }

    // Target method annotations
    ExecutableElement methodElt = TreeUtils.elementFromUse(tree);
    if (debugSpew) {
      System.err.println("methodElt found");
    }

    Tree callerTree = TreePathUtil.enclosingMethodOrLambda(getCurrentPath());
    if (callerTree == null) {
      // Static initializer; let's assume this is safe to have the UI effect
      if (debugSpew) {
        System.err.println("No enclosing method: likely static initializer");
      }
      return super.visitMethodInvocation(tree, p);
    }
    if (debugSpew) {
      System.err.println("callerTree found: " + callerTree.getKind());
    }

    Effect targetEffect = atypeFactory.getComputedEffectAtCallsite(tree, receiverType, methodElt);

    Effect callerEffect = null;
    if (callerTree instanceof MethodTree) {
      ExecutableElement callerElt = TreeUtils.elementFromDeclaration((MethodTree) callerTree);
      if (debugSpew) {
        System.err.println("callerElt found");
      }

      callerEffect = atypeFactory.getDeclaredEffect(callerElt);
      DeclaredType callerReceiverType = classType.getUnderlyingType();
      assert callerReceiverType != null;
      TypeElement callerReceiverElt = (TypeElement) callerReceiverType.asElement();
      // Note: All these checks should be fast in the common case, but happen for every method
      // call inside the anonymous class. Consider a cache here if profiling surfaces this as
      // taking too long.
      if (TypesUtils.isAnonymous(callerReceiverType)
          // Skip if already inferred @UI
          && !effStack.peek().isUI()
          // Ignore if explicitly annotated
          && !atypeFactory.fromElement(callerReceiverElt).hasPrimaryAnnotation(AlwaysSafe.class)
          && !atypeFactory.fromElement(callerReceiverElt).hasPrimaryAnnotation(UI.class)) {
        boolean overridesPolymorphic = false;
        Map<AnnotatedTypeMirror.AnnotatedDeclaredType, ExecutableElement> overriddenMethods =
            AnnotatedTypes.overriddenMethods(elements, atypeFactory, callerElt);
        for (Map.Entry<AnnotatedTypeMirror.AnnotatedDeclaredType, ExecutableElement> pair :
            overriddenMethods.entrySet()) {
          AnnotatedTypeMirror.AnnotatedDeclaredType overriddenType = pair.getKey();
          AnnotatedExecutableType overriddenMethod =
              AnnotatedTypes.asMemberOf(types, atypeFactory, overriddenType, pair.getValue());
          if (atypeFactory.getDeclAnnotation(overriddenMethod.getElement(), PolyUIEffect.class)
                  != null
              && atypeFactory.getDeclAnnotation(
                      overriddenType.getUnderlyingType().asElement(), PolyUIType.class)
                  != null) {
            overridesPolymorphic = true;
            break;
          }
        }
        // Perform anonymous class polymorphic effect inference:
        // method overrides @PolyUIEffect method of @PolyUIClass class, calls @UIEffect =>
        // @UI anon class
        if (overridesPolymorphic && targetEffect.isUI()) {
          // Mark the anonymous class as @UI
          atypeFactory.constrainAnonymousClassToUI(callerReceiverElt);
          // Then re-calculate this method's effect (it might still not be an
          // @PolyUIEffect method).
          callerEffect = atypeFactory.getDeclaredEffect(callerElt);
          effStack.pop();
          effStack.push(callerEffect);
        }
      }

      // Field initializers inside anonymous inner classes show up with a null current-method
      // --- the traversal goes straight from the class to the initializer.
      assert (currentMethods.peek() == null || callerEffect.equals(effStack.peek()));
    } else if (callerTree instanceof LambdaExpressionTree) {
      callerEffect =
          atypeFactory.getInferedEffectForLambdaExpression((LambdaExpressionTree) callerTree);
      // Perform lambda polymorphic effect inference: @PolyUI lambda, calling @UIEffect => @UI
      // lambda
      if (targetEffect.isUI() && callerEffect.isPoly()) {
        atypeFactory.constrainLambdaToUI((LambdaExpressionTree) callerTree);
        callerEffect = new Effect(UIEffect.class);
      }
    }
    assert callerEffect != null;

    if (!Effect.lessThanOrEqualTo(targetEffect, callerEffect)) {
      checker.reportError(tree, "call.ui", targetEffect, callerEffect);
      if (debugSpew) {
        System.err.println("Issuing error for tree: " + tree);
      }
    }
    if (debugSpew) {
      System.err.println("Successfully finished main non-recursive checkinv of invocation " + tree);
    }
    return super.visitMethodInvocation(tree, p);
  }

  @Override
  public void processMethodTree(String className, MethodTree tree) {
    AnnotatedExecutableType methodType = atypeFactory.getAnnotatedType(tree).deepCopy();
    AnnotatedDeclaredType previousReceiverType = receiverType;
    receiverType = methodType.getReceiverType();

    // TODO: If the type we're in is a polymorphic (over effect qualifiers) type, the receiver
    // must be @PolyUI.  Otherwise a "non-polymorphic" method of a polymorphic type could be
    // called on a UI instance, which then gets a Safe reference to itself (unsound!) that it
    // can then pass off elsewhere (dangerous!).  So all receivers in methods of a @PolyUIType
    // must be @PolyUI.

    // TODO: What do we do then about classes that inherit from a concrete instantiation?  If it
    // subclasses a Safe instantiation, all is well.  If it subclasses a UI instantiation, then
    // the receivers should probably be @UI in both new and override methods, so calls to
    // polymorphic methods of the parent class will work correctly.  In which case for proving
    // anything, the qualifier on sublasses of UI instantiations would always have to be @UI...
    // Need to write down |- t for this system!  And the judgments for method overrides and
    // inheritance!  Those are actually the hardest part of the system.

    ExecutableElement methElt = TreeUtils.elementFromDeclaration(tree);
    if (debugSpew) {
      System.err.println("Visiting method " + methElt + " of " + methElt.getEnclosingElement());
    }

    // Check for conflicting (multiple) annotations
    assert (methElt != null);
    // TypeMirror scratch = methElt.getReturnType();
    AnnotationMirror targetUIP = atypeFactory.getDeclAnnotation(methElt, UIEffect.class);
    AnnotationMirror targetSafeP = atypeFactory.getDeclAnnotation(methElt, SafeEffect.class);
    AnnotationMirror targetPolyP = atypeFactory.getDeclAnnotation(methElt, PolyUIEffect.class);
    TypeElement targetClassElt = (TypeElement) methElt.getEnclosingElement();

    if ((targetUIP != null && (targetSafeP != null || targetPolyP != null))
        || (targetSafeP != null && targetPolyP != null)) {
      checker.reportError(tree, "annotations.conflicts");
    }
    if (targetPolyP != null && !atypeFactory.isPolymorphicType(targetClassElt)) {
      checker.reportError(tree, "polymorphism");
    }
    if (targetUIP != null && atypeFactory.isUIType(targetClassElt)) {
      checker.reportWarning(tree, "effects.redundant.uitype");
    }

    // TODO: Report an error for polymorphic method bodies??? Until we fix the receiver
    // defaults, it won't really be correct
    @SuppressWarnings("unused") // call has side effects
    Effect.EffectRange range =
        atypeFactory.findInheritedEffectRange(
            ((TypeElement) methElt.getEnclosingElement()), methElt, true, tree);
    // if (targetUIP == null && targetSafeP == null && targetPolyP == null) {
    // implicitly annotate this method with the LUB of the effects of the methods it overrides
    // atypeFactory.fromElement(methElt).addAnnotation(range != null ? range.min.getAnnot()
    // : (isUIType(((TypeElement)methElt.getEnclosingElement())) ? UI.class :
    // AlwaysSafe.class));
    // TODO: This line does nothing! AnnotatedTypeMirror.addAnnotation
    // silently ignores non-qualifier annotations!
    // System.err.println("ERROR: TREE ANNOTATOR SHOULD HAVE ADDED EXPLICIT ANNOTATION! ("
    //     +tree.getName()+")");
    // atypeFactory
    //         .fromElement(methElt)
    //         .addAnnotation(atypeFactory.getDeclaredEffect(methElt).getAnnot());
    // }

    // We hang onto the current method here for ease.  We back up the old
    // current method because this code is reentrant when we traverse methods of an inner class
    currentMethods.addFirst(tree);
    // effStack.push(targetSafeP != null ? new Effect(AlwaysSafe.class) :
    //                (targetPolyP != null ? new Effect(PolyUI.class) :
    //                   (targetUIP != null ? new Effect(UI.class) :
    //                      (range != null ? range.min :
    // (isUIType(((TypeElement)methElt.getEnclosingElement())) ? new Effect(UI.class) : new
    // Effect(AlwaysSafe.class))))));
    effStack.addFirst(atypeFactory.getDeclaredEffect(methElt));
    if (debugSpew) {
      System.err.println("Pushing " + effStack.peek() + " onto the stack when checking " + methElt);
    }

    super.processMethodTree(className, tree);
    currentMethods.removeFirst();
    effStack.removeFirst();
    receiverType = previousReceiverType;
  }

  @Override
  @SuppressWarnings("interning:not.interned") // comparing AST nodes
  public Void visitNewClass(NewClassTree tree, Void p) {
    Void v = super.visitNewClass(tree, p);
    // If this is an anonymous inner class inferred to be @UI, scan up the path and re-check any
    // assignments involving it.
    if (atypeFactory.isDirectlyMarkedUIThroughInference(tree)) {
      // Backtrack path to the new class expression itself
      TreePath path = getCurrentPath();
      while (path.getLeaf() != tree) {
        assert !(path.getLeaf() instanceof CompilationUnitTree);
        path = path.getParentPath();
      }
      scanUp(getCurrentPath().getParentPath());
    }
    return v;
  }

  /**
   * This method is called to traverse the path back up from any anonymous inner class or lambda
   * which has been inferred to be UI affecting and re-run {@code commonAssignmentCheck()} as needed
   * on places where the class declaration or lambda expression are being assigned to a variable,
   * passed as a parameter or returned from a method. This is necessary because the normal visitor
   * traversal only checks assignments on the way down the AST, before inference has had a chance to
   * run.
   *
   * @param path the path to traverse up from a UI-affecting class
   */
  private void scanUp(TreePath path) {
    Tree tree = path.getLeaf();
    switch (tree.getKind()) {
      case ASSIGNMENT:
        AssignmentTree assignmentTree = (AssignmentTree) tree;
        commonAssignmentCheck(
            atypeFactory.getAnnotatedType(assignmentTree.getVariable()),
            atypeFactory.getAnnotatedType(assignmentTree.getExpression()),
            assignmentTree.getExpression(),
            "assignment");
        break;
      case VARIABLE:
        VariableTree variableTree = (VariableTree) tree;
        commonAssignmentCheck(
            atypeFactory.getAnnotatedType(variableTree),
            atypeFactory.getAnnotatedType(variableTree.getInitializer()),
            variableTree.getInitializer(),
            "assignment");
        break;
      case METHOD_INVOCATION:
        MethodInvocationTree invocationTree = (MethodInvocationTree) tree;
        List<? extends ExpressionTree> args = invocationTree.getArguments();
        ParameterizedExecutableType mType = atypeFactory.methodFromUse(invocationTree);
        AnnotatedExecutableType invokedMethod = mType.executableType;
        ExecutableElement method = invokedMethod.getElement();
        CharSequence methodName = ElementUtils.getSimpleDescription(method);
        List<? extends VariableElement> methodParams = method.getParameters();
        List<AnnotatedTypeMirror> paramTypes =
            AnnotatedTypes.adaptParameters(
                atypeFactory, invokedMethod, invocationTree.getArguments(), invocationTree);
        for (int i = 0; i < args.size(); ++i) {
          if (args.get(i) instanceof NewClassTree || args.get(i) instanceof LambdaExpressionTree) {
            commonAssignmentCheck(
                paramTypes.get(i),
                atypeFactory.getAnnotatedType(args.get(i)),
                args.get(i),
                "argument",
                methodParams.get(i),
                methodName);
          }
        }
        break;
      case RETURN:
        ReturnTree returnTree = (ReturnTree) tree;
        if (returnTree.getExpression() instanceof NewClassTree
            || returnTree.getExpression() instanceof LambdaExpressionTree) {
          Tree enclosing = TreePathUtil.enclosingMethodOrLambda(path);
          AnnotatedTypeMirror ret = null;
          if (enclosing instanceof MethodTree) {
            MethodTree enclosingMethod = (MethodTree) enclosing;
            boolean valid = validateTypeOf(enclosing);
            if (valid) {
              ret = atypeFactory.getMethodReturnType(enclosingMethod, returnTree);
            }
          } else {
            ret =
                atypeFactory
                    .getFunctionTypeFromTree((LambdaExpressionTree) enclosing)
                    .getReturnType();
          }

          if (ret != null) {
            commonAssignmentCheck(
                ret,
                atypeFactory.getAnnotatedType(returnTree.getExpression()),
                returnTree.getExpression(),
                "return");
          }
        }
        break;
      case METHOD:
        // Stop scanning at method boundaries, since the expression can't escape the method
        // without either being assigned to a field or returned.
        return;
      case CLASS:
        // Can't ever happen, because we stop scanning at either method or field initializer
        // boundaries
        assert false;
        return;
      default:
        scanUp(path.getParentPath());
    }
  }

  // @Override
  // public Void visitMemberSelect(MemberSelectTree tree, Void p) {
  // TODO: Same effect checks as for methods
  // return super.visitMemberSelect(tree, p);
  // }

  // @Override
  // public void processClassTree(ClassTree tree) {
  // TODO: Check constraints on this class decl vs. parent class decl., and interfaces
  // TODO: This has to wait for now: maybe this will be easier with the isValidUse on the
  // TypeFactory.
  // AnnotatedTypeMirror.AnnotatedDeclaredType atype = atypeFactory.fromClass(tree);

  // Push a null method and UI effect onto the stack for static field initialization
  // TODO: Figure out if this is safe! For static data, almost certainly,
  // but for statically initialized instance fields, I'm assuming those
  // are implicitly moved into each constructor, which must then be @UI.
  // currentMethods.addFirst(null);
  // effStack.addFirst(new Effect(UIEffect.class));
  // super.processClassTree(tree);
  // currentMethods.removeFirst();
  // effStack.removeFirst();
  // }

  @Override
  public void processClassTree(ClassTree classTree) {
    AnnotatedDeclaredType previousClassType = classType;
    AnnotatedDeclaredType previousReceiverType = receiverType;
    receiverType = null;
    classType = atypeFactory.getAnnotatedType(TreeUtils.elementFromDeclaration(classTree));
    try {
      super.processClassTree(classTree);
    } finally {
      classType = previousClassType;
      receiverType = previousReceiverType;
    }
  }
}
