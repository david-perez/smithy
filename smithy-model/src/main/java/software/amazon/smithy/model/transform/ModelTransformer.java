/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.model.transform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.ModelAssembler;
import software.amazon.smithy.model.neighbor.UnreferencedShapes;
import software.amazon.smithy.model.neighbor.UnreferencedTraitDefinitions;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ServiceShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeType;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.utils.FunctionalUtils;
import software.amazon.smithy.utils.ListUtils;

/**
 * Class used to transform {@link Model}s.
 */
public final class ModelTransformer {
    private final List<ModelTransformerPlugin> plugins;

    private ModelTransformer(List<ModelTransformerPlugin> plugins) {
        this.plugins = ListUtils.copyOf(plugins);
    }

    /**
     * Creates a ModelTransformer using ModelTransformerPlugin instances
     * discovered using the {@code com.software.smithy.transform} class
     * loader and any modules found in the module path.
     *
     * @return Returns the created ModelTransformer.
     */
    public static ModelTransformer create() {
        return DefaultHolder.INSTANCE;
    }

    // Lazy initialization holder class idiom
    private static class DefaultHolder {
        static final ModelTransformer INSTANCE = createWithServiceProviders(ModelTransformer.class.getClassLoader());
    }

    private static ModelTransformer createWithServiceLoader(ServiceLoader<ModelTransformerPlugin> serviceLoader) {
        List<ModelTransformerPlugin> plugins = new ArrayList<>();
        serviceLoader.forEach(plugins::add);
        return createWithPlugins(plugins);
    }

    /**
     * Creates a ModelTransformer using a list of ModelTransformer plugins.
     *
     * @param plugins Plugins to use with the transformer.
     * @return Returns the created ModelTransformer.
     */
    public static ModelTransformer createWithPlugins(List<ModelTransformerPlugin> plugins) {
        return new ModelTransformer(plugins);
    }

    /**
     * Creates a ModelTransformer that finds {@link ModelTransformerPlugin}
     * service providers using the given {@link ClassLoader}.
     *
     * @param classLoader ClassLoader used to find ModelTransformerPlugin instances.
     * @return Returns the created ModelTransformer.
     */
    public static ModelTransformer createWithServiceProviders(ClassLoader classLoader) {
        return createWithServiceLoader(ServiceLoader.load(ModelTransformerPlugin.class, classLoader));
    }

    /**
     * Adds or replaces shapes into the model while ensuring that the model
     * is in a consistent state.
     *
     * @param model Model to transform.
     * @param shapes Shapes to add or replace in the model.base.
     * @return Returns the transformed model.base.
     */
    public Model replaceShapes(Model model, Collection<Shape> shapes) {
        if (shapes.isEmpty()) {
            return model;
        }

        return new ReplaceShapes(shapes).transform(this, model);
    }

    /**
     * Removes shapes from the model while ensuring that the model is in a
     * consistent state.
     *
     * @param model Model to transform.
     * @param shapes Shapes to add or replace in the model.base.
     * @return Returns the transformed model.base.
     */
    public Model removeShapes(Model model, Collection<Shape> shapes) {
        if (shapes.isEmpty()) {
            return model;
        }

        return new RemoveShapes(shapes, plugins).transform(this, model);
    }

    /**
     * Removes shapes from the model that match the given predicate.
     *
     * @param model Model to transform.
     * @param predicate Predicate that accepts a shape and returns true to
     *  remove it.
     * @return Returns the transformed model.base.
     */
    public Model removeShapesIf(Model model, Predicate<Shape> predicate) {
        return filterShapes(model, FunctionalUtils.not(predicate));
    }

    /**
     *  Renames shapes using ShapeId pairs while ensuring that the
     *  transformed model is in a consistent state.
     *
     *  <p>This transformer ensures that when an aggregate shape is renamed, all
     *  members are updated in the model.
     *
     * @param model Model to transform.
     * @param renamed Map of shapeIds
     * @return Returns the transformed model.base.
     */
    public Model renameShapes(
            Model model,
            Map<ShapeId, ShapeId> renamed
    ) {
        return this.renameShapes(model, renamed, () -> Model.assembler().disableValidation());
    }

    /**
     *  Renames shapes using ShapeId pairs while ensuring that the
     *  transformed model is in a consistent state.
     *
     *  <p>This transformer ensures that when an aggregate shape is renamed, all
     *  members are updated in the model.
     *
     * @param model Model to transform.
     * @param renamed Map of shapeIds
     * @param modelAssemblerSupplier Supplier used to create {@link ModelAssembler}s in each transform.
     * @return Returns the transformed model.
     */
    public Model renameShapes(
            Model model,
            Map<ShapeId, ShapeId> renamed,
            Supplier<ModelAssembler> modelAssemblerSupplier
    ) {
        return new RenameShapes(renamed, modelAssemblerSupplier).transform(this, model);
    }

    /**
     * Filters shapes out of the model that do not match the given predicate.
     *
     * <p>This filter will never filter out shapes that are part of the
     * prelude. Use the {@link #removeShapes} method directly if you need
     * to remove traits that are in the prelude.
     *
     * @param model Model to transform.
     * @param predicate Predicate that filters shapes.
     * @return Returns the transformed model.
     */
    public Model filterShapes(Model model, Predicate<Shape> predicate) {
        return new FilterShapes(predicate).transform(this, model);
    }

    /**
     * Filters traits out of the model that do not match the given predicate.
     *
     * <p>The predicate function accepts the shape that a trait is attached to
     * and the trait. If the predicate returns false, then the trait is
     * removed from the shape.
     *
     * @param model Model to transform.
     * @param predicate Predicate that accepts a (Shape, Trait) and returns
     *  false if the trait should be removed.
     * @return Returns the transformed model.base.
     */
    public Model filterTraits(Model model, BiPredicate<Shape, Trait> predicate) {
        return new FilterTraits(predicate).transform(this, model);
    }

    /**
     * Filters traits out of the model that match a predicate function.
     *
     * <p>The predicate function accepts the shape that a trait is attached to
     * and the trait. If the predicate returns true, then the trait is removed
     * from the shape.
     *
     * @param model Model to transform.
     * @param predicate Predicate that accepts a (Shape, Trait) and returns
     *  true if the trait should be removed.
     * @return Returns the transformed model.base.
     */
    public Model removeTraitsIf(Model model, BiPredicate<Shape, Trait> predicate) {
        return filterTraits(model, predicate.negate());
    }

    /**
     * Filters out metadata key-value pairs from a model that do not match
     * a predicate.
     *
     * @param model Model to transform.
     * @param predicate A predicate that accepts a metadata key-value pair.
     *  If the predicate returns true, then the metadata key-value pair is
     *  kept. Otherwise, it is removed.
     * @return Returns the transformed model.base.
     */
    public Model filterMetadata(Model model, BiPredicate<String, Node> predicate) {
        return new FilterMetadata(predicate).transform(model);
    }

    /**
     * Maps over all traits in the model using a mapping function that accepts
     * the shape the trait is applied to, a trait, and returns a trait,
     * possibly even a different kind of trait.
     *
     * <p>An exception is thrown if a trait is returned that targets a
     * different shape than the {@link Shape} passed into the mapper function.
     *
     * @param model Model to transform.
     * @param mapper Mapping function that accepts a (Shape, Trait) and returns
     *  the mapped Trait.
     * @return Returns the transformed model.base.
     */
    public Model mapTraits(Model model, BiFunction<Shape, Trait, Trait> mapper) {
        return new MapTraits(mapper).transform(this, model);
    }

    /**
     * Maps over all traits in the model using multiple mapping functions.
     *
     * <p>Note: passing in a list of mappers is much more efficient than
     * invoking {@code mapTraits} multiple times because it reduces the number
     * of intermediate models that are needed to perform the transformation.
     *
     * @param model Model to transform.
     * @param mappers Mapping functions that accepts a (Shape, Trait) and
     *  returns the mapped Trait.
     * @return Returns the transformed model.base.
     * @see #mapShapes(Model, Function) for more information.
     */
    public Model mapTraits(Model model, List<BiFunction<Shape, Trait, Trait>> mappers) {
        return mapTraits(model, mappers.stream()
                .reduce((a, b) -> (s, t) -> b.apply(s, a.apply(s, t)))
                .orElse((s, t) -> t));
    }

    /**
     * Maps over all shapes in the model using a mapping function, allowing
     * shapes to be replaced with completely different shapes or slightly
     * modified shapes.
     *
     * <p>An exception is thrown if a mapper returns a shape with a different
     * shape ID or a different type.
     *
     * @param model Model to transform.
     * @param mapper Mapping function that accepts a shape and returns a shape
     *  with the same ID.
     * @return Returns the transformed model.base.
     */
    public Model mapShapes(Model model, Function<Shape, Shape> mapper) {
        return new MapShapes(mapper).transform(this, model);
    }

    /**
     * Maps over all shapes in the model using multiple mapping functions.
     *
     * <p>Note: passing in a list of mappers is much more efficient than
     * invoking {@code mapShapes}  multiple times because it reduces the
     * number of intermediate models that are needed to perform the
     * transformation.
     *
     * @param model Model to transform.
     * @param mappers Mapping functions that accepts a shape and returns a
     *  shape with the same ID.
     * @return Returns the transformed model.base.
     * @see #mapShapes(Model, Function) for more information.
     */
    public Model mapShapes(Model model, List<Function<Shape, Shape>> mappers) {
        return mapShapes(model, (mappers.stream().reduce(Function::compose).orElse(Function.identity())));
    }

    /**
     * Removes shapes (excluding service shapes) that are not referenced by
     * any other shapes.
     *
     * @param model Model to transform.
     * @return Returns the transformed model.base.
     */
    public Model removeUnreferencedShapes(Model model) {
        return removeUnreferencedShapes(model, FunctionalUtils.alwaysTrue());
    }

    /**
     * Removes shapes (excluding service shapes) that are not referenced by
     * any other shapes.
     *
     * Shapes that are part of the prelude or that act as the shape of any
     * trait, regardless of if the trait is in use in the model, are never
     * considered unreferenced.
     *
     * @param model Model to transform.
     * @param keepFilter Predicate function that accepts an unreferenced
     *  shape and returns true to remove the shape or false to keep the shape
     *  in the model.base.
     * @return Returns the transformed model.base.
     */
    public Model removeUnreferencedShapes(Model model, Predicate<Shape> keepFilter) {
        return removeShapes(model, new UnreferencedShapes(keepFilter).compute(model));
    }

    /**
     * Removes definitions for traits that are not used by any shape in the
     * model.base.
     *
     * Trait definitions that are part of the prelude will not be removed.
     *
     * @param model Model to transform
     * @return Returns the transformed model.base.
     */
    public Model removeUnreferencedTraitDefinitions(Model model) {
        return removeUnreferencedTraitDefinitions(model, FunctionalUtils.alwaysTrue());
    }

    /**
     * Removes trait definitions for traits that are not used by any shape
     * in the model.
     *
     * <p>Trait definitions that are part of the prelude will not be removed.
     *
     * @param model Model to transform
     * @param keepFilter Predicate function that accepts an unreferenced trait
     *  shape (that has the {@link TraitDefinition} trait) and returns true to
     *  remove the definition or false to keep the definition in the model.base.
     * @return Returns the transformed model.base.
     */
    public Model removeUnreferencedTraitDefinitions(Model model, Predicate<Shape> keepFilter) {
        return removeShapes(model, new UnreferencedTraitDefinitions(keepFilter).compute(model));
    }

    /**
     * Removes all trait definitions from a model and all shapes that are
     * only connected to the graph either directly or transitively by a
     * trait definition shape.
     *
     * <p>This can be useful when serializing a Smithy model to a format that
     * does not include trait definitions and the shapes used by trait definitions
     * would have no meaning (e.g., OpenAPI).
     *
     * @param model Model to transform.
     * @return Returns the transformed model.base.
     */
    public Model scrubTraitDefinitions(Model model) {
        return scrubTraitDefinitions(model, FunctionalUtils.alwaysTrue());
    }

    /**
     * Removes trait definitions from a model and all shapes that are
     * only connected to the graph either directly or transitively by a
     * trait definition shape.
     *
     * <p>This can be useful when serializing a Smithy model to a format that
     * does not include trait definitions and the shapes used by trait definitions
     * would have no meaning (e.g., OpenAPI).
     *
     * @param model Model to transform.
     * @param keepFilter Predicate function that accepts an trait shape (that
     *  has the {@link TraitDefinition} trait) and returns true to remove the
     *  definition or false to keep the definition in the model.
     * @return Returns the transformed model.
     */
    public Model scrubTraitDefinitions(Model model, Predicate<Shape> keepFilter) {
        return new ScrubTraitDefinitions().transform(this, model, keepFilter);
    }

    /**
     * Gets all shapes from a model where shapes that define traits or shapes
     * that are only used as part of a trait definition have been removed.
     *
     * @param model Model that contains shapes.
     * @return Returns a model that contains matching shapes.
     */
    public Model getModelWithoutTraitShapes(Model model) {
        return getModelWithoutTraitShapes(model, FunctionalUtils.alwaysTrue());
    }

    /**
     * Gets all shapes from a model where shapes that define traits or shapes
     * that are only used as part of a trait definition have been removed.
     *
     * @param model Model that contains shapes.
     * @param keepFilter Predicate function that accepts a trait shape (that
     *  has the {@link TraitDefinition} trait) and returns true to remove the
     *  definition or false to keep the definition in the model.
     * @return Returns a model that contains matching shapes.
     */
    public Model getModelWithoutTraitShapes(Model model, Predicate<Shape> keepFilter) {
        Model.Builder builder = Model.builder();

        // ScrubTraitDefinitions is used to removed traits and trait shapes.
        // However, the returned model can't be returned directly because
        // as traits are removed, uses of that trait are removed. Instead,
        // a model is created by getting all shape IDs from the modified
        // model, grabbing shapes from the original model, and building a new
        // Model.
        scrubTraitDefinitions(model, keepFilter).shapes()
                .map(Shape::getId)
                .map(model::getShape)
                .map(Optional::get)
                .forEach(builder::addShape);

        return builder.build();
    }

    /**
     * Reorders the members of structure and union shapes using the given
     * {@link Comparator}.
     *
     * <p>Note that by default, Smithy models retain the order in which
     * members are defined in the model. However, in programming languages
     * where this isn't important, it may be desirable to order members
     * alphabetically or using some other kind of order.
     *
     * @param model Model that contains shapes.
     * @param comparator Comparator used to order members of unions and structures.
     * @return Returns a model that contains matching shapes.
     */
    public Model sortMembers(Model model, Comparator<MemberShape> comparator) {
        return new SortMembers(comparator).transform(this, model);
    }

    /**
     * Changes the type of each given shape.
     *
     * <p>The following transformations are permitted:
     *
     * <ul>
     *     <li>Any simple type to any simple type</li>
     *     <li>List to set</li>
     *     <li>Set to list</li>
     *     <li>Structure to union</li>
     *     <li>Union to structure</li>
     * </ul>
     *
     * @param model Model to transform.
     * @param shapeToType Map of shape IDs to the new type to use for the shape.
     * @return Returns the transformed model.
     * @throws ModelTransformException if an incompatible type transform is attempted.
     */
    public Model changeShapeType(Model model, Map<ShapeId, ShapeType> shapeToType) {
        return new ChangeShapeType(shapeToType).transform(this, model);
    }

    /**
     * Copies the errors defined on the given service onto each operation bound to the
     * service, effectively flattening service error inheritance.
     *
     * @param model Model to modify.
     * @param forService Service shape to use as the basis for copying errors to operations.
     * @return Returns the transformed model.
     */
    public Model copyServiceErrorsToOperations(Model model, ServiceShape forService) {
        return new CopyServiceErrorsToOperationsTransform(forService).transform(this, model);
    }

    /**
     * Flattens mixins out of the model and removes them from the model.
     *
     * @param model Model to flatten.
     * @return Returns the flattened model.
     */
    public Model flattenAndRemoveMixins(Model model) {
        return new FlattenAndRemoveMixins().transform(this, model);
    }
}
