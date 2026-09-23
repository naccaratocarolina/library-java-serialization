package org.coldis.library.serialization;

import java.lang.annotation.Annotation;
import java.lang.annotation.Inherited;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.fory.BaseFory;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.ForyBuilder;
import org.apache.fory.config.Language;
import org.coldis.library.model.Typable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AbstractTypeHierarchyTraversingFilter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

/**
 * Optimized serialization helper.
 */
public class OptimizedSerializationHelper {

	/**
	 * Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(OptimizedSerializationHelper.class);

	/**
	 * Default coldis package always added to the scan so hand-written DTO
	 * parents (e.g. {@code org.coldis.library.model.AbstractTimestampable})
	 * end up registered in Fory without callers having to remember to add
	 * it to their {@code base-package}.
	 */
	private static final String COLDIS_MODEL_PACKAGE = "org.coldis.library.model";

	/**
	 * Selects which subset of scanned classes the optimized serializer should
	 * register. Driven by the {@link org.coldis.library.dto.DtoType} mappings
	 * walked at scan time — for paired (Model, DTO) classes:
	 *
	 * <ul>
	 *   <li>{@link #ALL} — register both. Suitable for producer JVMs that need
	 *       to emit either side, or for tests that want to exercise both
	 *       directions of cross-class round-trips.</li>
	 *   <li>{@link #MODELS} — register Models only; paired DTOs are skipped.
	 *       Use on consumer JVMs that only ever read into Model types.</li>
	 *   <li>{@link #DTOS} — register DTOs only; paired Models are skipped.
	 *       Use on producer JVMs that only emit DTO instances (e.g. a service
	 *       that ships only the {@code service-client} jar).</li>
	 * </ul>
	 *
	 * <p>Classes that are not part of any (Model, DTO) pair are always
	 * registered regardless of the scope.
	 */
	public enum RegistrationScope {
		ALL,
		MODELS,
		DTOS;
	}

	/**
	 * Annotation classes whose presence excludes a scanned class from
	 * Fory registration. AspectJ is loaded softly so this module does
	 * not need a compile-time dependency on aspectjrt.
	 */
	private static final Set<Class<? extends Annotation>> EXCLUDED_ANNOTATIONS = OptimizedSerializationHelper.buildExcludedAnnotations();

	@SuppressWarnings("unchecked")
	private static Set<Class<? extends Annotation>> buildExcludedAnnotations() {
		final java.util.LinkedHashSet<Class<? extends Annotation>> excluded = new java.util.LinkedHashSet<>();
		excluded.add(Component.class);
		excluded.add(Controller.class);
		excluded.add(Service.class);
		excluded.add(Repository.class);
		excluded.add(Configuration.class);
		try {
			excluded.add((Class<? extends Annotation>) Class.forName("org.aspectj.lang.annotation.Aspect"));
		}
		catch (final ClassNotFoundException notOnClasspath) {
			OptimizedSerializationHelper.LOGGER.debug("AspectJ @Aspect not on classpath; skipping from exclusion set.");
		}
		return Set.copyOf(excluded);
	}

	/**
	 * No annotation type filter.
	 */
	public static class NoAnnotationTypeFilter extends AbstractTypeHierarchyTraversingFilter {

		/** Without annotations. */
		private final Set<Class<? extends Annotation>> withoutAnnotations;

		/** Consider meta-annotations. */
		private final boolean considerMetaAnnotations;

		/**
		 * Constructor.
		 *
		 * @param annotations             Annotations.
		 * @param considerMetaAnnotations Consider meta-annotations.
		 * @param considerInterfaces      Consider interfaces.
		 */
		public NoAnnotationTypeFilter(
				final Set<Class<? extends Annotation>> withoutAnnotations,
				final boolean considerMetaAnnotations,
				final boolean considerInterfaces) {
			super(withoutAnnotations.stream().anyMatch(annotationType -> annotationType.isAnnotationPresent(Inherited.class)), considerInterfaces);
			this.withoutAnnotations = withoutAnnotations;
			this.considerMetaAnnotations = considerMetaAnnotations;
		}

		/**
		 * Checks if the class has any annotation.
		 *
		 * @param  typeName Type name.
		 * @return          If the class has any annotation.
		 */
		@Nullable
		protected Boolean hasAnyAnnotation(
				final String typeName) {
			Boolean hasAnnotations = false;
			try {
				final Class<?> clazz = ClassUtils.forName(typeName, this.getClass().getClassLoader());
				hasAnnotations = this.withoutAnnotations.stream()
						.anyMatch(annotationType -> ((this.considerMetaAnnotations ? AnnotationUtils.getAnnotation(clazz, annotationType)
								: clazz.getAnnotation(annotationType)) != null));
			}
			catch (final Throwable throwable) { // Ignores.
			}
			return hasAnnotations;
		}

		/**
		 * @see org.springframework.core.type.filter.AnnotationTypeFilter#matchSelf(org.springframework.core.type.classreading.MetadataReader)
		 */
		@Override
		protected boolean matchSelf(
				final MetadataReader metadataReader) {
			final AnnotationMetadata metadata = metadataReader.getAnnotationMetadata();
			return this.withoutAnnotations.stream().noneMatch(annotationType -> metadata.hasAnnotation(annotationType.getName())
					|| (this.considerMetaAnnotations && metadata.hasMetaAnnotation(annotationType.getName())));
		}

		/**
		 * @see org.springframework.core.type.filter.AnnotationTypeFilter#matchSuperClass(java.lang.String)
		 */
		@Override
		protected Boolean matchSuperClass(
				final String superClassName) {
			return !this.hasAnyAnnotation(superClassName);
		}

		/**
		 * @see org.springframework.core.type.filter.AnnotationTypeFilter#matchInterface(java.lang.String)
		 */
		@Override
		protected Boolean matchInterface(
				final String interfaceName) {
			return !this.hasAnyAnnotation(interfaceName);
		}

	}

	/**
	 * @deprecated The no-scope overload defaults to {@link RegistrationScope#MODELS}
	 *             (preserving the historical Model-canonical typeName behavior).
	 *             Pick a scope explicitly via the named factories
	 *             ({@link #createAllSerializer}, {@link #createModelSerializer},
	 *             {@link #createDtoSerializer}) or the
	 *             {@link RegistrationScope}-aware overload.
	 */
	@Deprecated
	public static final BaseFory createSerializer(
			final Boolean threadSafe,
			final Integer minPoolSize,
			final Integer maxPoolSize,
			final Language language,
			final String... packagesNames) {
		return OptimizedSerializationHelper.createSerializer(threadSafe, minPoolSize, maxPoolSize, language, RegistrationScope.MODELS, packagesNames);
	}

	/**
	 * Creates a serializer that registers every scanned class — both Models and
	 * their DTO partners. Use for tests or producer JVMs that need to emit
	 * either side of a (Model, DTO) pair.
	 */
	public static final BaseFory createAllSerializer(
			final Boolean threadSafe,
			final Integer minPoolSize,
			final Integer maxPoolSize,
			final Language language,
			final String... packagesNames) {
		return OptimizedSerializationHelper.createSerializer(threadSafe, minPoolSize, maxPoolSize, language, RegistrationScope.ALL, packagesNames);
	}

	/**
	 * Creates a serializer that registers Models only — DTOs paired with a
	 * Model (via {@link org.coldis.library.dto.DtoType}) are skipped.
	 * Suitable for consumer JVMs that read messages straight into Model
	 * types.
	 */
	public static final BaseFory createModelSerializer(
			final Boolean threadSafe,
			final Integer minPoolSize,
			final Integer maxPoolSize,
			final Language language,
			final String... packagesNames) {
		return OptimizedSerializationHelper.createSerializer(threadSafe, minPoolSize, maxPoolSize, language, RegistrationScope.MODELS, packagesNames);
	}

	/**
	 * Creates a serializer that registers DTOs only — Models paired with a
	 * DTO are skipped. Suitable for producer JVMs that emit DTO instances
	 * (e.g. a service that ships only the {@code service-client} jar).
	 */
	public static final BaseFory createDtoSerializer(
			final Boolean threadSafe,
			final Integer minPoolSize,
			final Integer maxPoolSize,
			final Language language,
			final String... packagesNames) {
		return OptimizedSerializationHelper.createSerializer(threadSafe, minPoolSize, maxPoolSize, language, RegistrationScope.DTOS, packagesNames);
	}

	/**
	 * @deprecated Use {@link #createSerializer(Boolean, Integer, Integer, Language, RegistrationScope, String...)}
	 *             or one of the named factories ({@link #createAllSerializer},
	 *             {@link #createModelSerializer}, {@link #createDtoSerializer}).
	 *             {@code preferDto=true} maps to {@link RegistrationScope#DTOS};
	 *             {@code preferDto=false} maps to {@link RegistrationScope#MODELS}.
	 */
	@Deprecated
	public static final BaseFory createSerializer(
			final Boolean threadSafe,
			final Integer minPoolSize,
			final Integer maxPoolSize,
			final Language language,
			final boolean preferDto,
			final String... packagesNames) {
		return OptimizedSerializationHelper.createSerializer(threadSafe, minPoolSize, maxPoolSize, language,
				preferDto ? RegistrationScope.DTOS : RegistrationScope.MODELS, packagesNames);
	}

	/**
	 * Creates a serializer. The {@link RegistrationScope} controls which
	 * classes from the scan are registered when (Model, DTO) pairs are
	 * detected via {@link org.coldis.library.dto.DtoType}.
	 *
	 * @param  threadSafe    Whether the serializer should be thread-safe.
	 * @param  minPoolSize   Min pool size (thread-safe pool).
	 * @param  maxPoolSize   Max pool size (thread-safe pool).
	 * @param  language      Language.
	 * @param  scope         Registration scope.
	 * @param  packagesNames Packages to scan for registration. The
	 *                           {@code org.coldis.library.model} package is
	 *                           always added so hand-written shared parents
	 *                           are reachable.
	 * @return               Serializer.
	 */
	public static final BaseFory createSerializer(
			final Boolean threadSafe,
			final Integer minPoolSize,
			final Integer maxPoolSize,
			final Language language,
			final RegistrationScope scope,
			final String... packagesNames) {
		// Enable reference tracking so shared sub-objects and cyclic graphs (common in Hibernate-
		// managed entity hierarchies) serialize and round-trip correctly. Without this, the second
		// visit to the same object — or a true cycle — throws during serialization, which on the
		// JMS path is silently caught by EnhancedJmsMessageConverter#toSerializedMessage and
		// downgrades the wire format to JSON.
		final ForyBuilder foryBuilder = Fory.builder().registerGuavaTypes(false).withLanguage(language).withCompatibleMode(CompatibleMode.COMPATIBLE)
				.withDeserializeUnknownClass(true).withRefTracking(true);
		foryBuilder.requireClassRegistration(ArrayUtils.isNotEmpty(packagesNames));
		final BaseFory fory = (threadSafe ? ((maxPoolSize != null) ? foryBuilder.buildThreadSafeForyPool(maxPoolSize)
				: foryBuilder.buildThreadSafeFory()) : foryBuilder.build());
		// Fory's auto-registration for deserializeUnknownClass=true only covers
		// UnknownStruct/UnknownEmptyStruct. UnknownEnum (and the array variants of all three) are
		// missed, which makes the security gate reject otherwise valid cross-class deserializations
		// when a DTO carries fields the Model doesn't declare. Register them explicitly so the
		// receiver can fall through to a placeholder instead of failing.
		// Note: The array placeholder fields (UnknownEnum1DArray, etc.) were removed in Fory 1.x.
		// We use reflection to detect them so this code stays compatible with both 0.x and 1.x.
		final Set<Class<?>> unknownPlaceholders = new HashSet<>();
		unknownPlaceholders.add(org.apache.fory.serializer.UnknownClass.UnknownEnum.class);
		unknownPlaceholders.add(org.apache.fory.serializer.UnknownClass.UnknownEmptyStruct.class);
		unknownPlaceholders.add(org.apache.fory.serializer.UnknownClass.UnknownStruct.class);
		// Try to add array placeholders via reflection (only present in Fory 0.x).
		final String[] arrayFieldNames = { "UnknownEnum1DArray", "UnknownEnum2DArray", "UnknownEnum3DArray",
				"UnknownEmptyStruct1DArray", "UnknownEmptyStruct2DArray", "UnknownEmptyStruct3DArray",
				"UnknownStruct1DArray", "UnknownStruct2DArray", "UnknownStruct3DArray" };
		for (final String fieldName : arrayFieldNames) {
			try {
				final java.lang.reflect.Field field = org.apache.fory.serializer.UnknownClass.class.getField(fieldName);
				final Object value = field.get(null);
				if (value instanceof Class<?>) {
					unknownPlaceholders.add((Class<?>) value);
				}
			}
			catch (final NoSuchFieldException | IllegalAccessException ignored) {
				// Field not present in this Fory version — expected for Fory 1.x.
			}
		}
		for (final Class<?> placeholder : unknownPlaceholders) {
			try {
				fory.register(placeholder);
			}
			catch (final Throwable ignore) {
				// Already registered (e.g. UnknownStruct on metaShare path) or not exposed by the
				// installed Fory version — both are acceptable.
			}
		}
		if (ArrayUtils.isNotEmpty(packagesNames)) {
			// Always include coldis core models so shared hand-written parents (e.g.
			// AbstractTimestampable used as a declared DTO via @DtoType.dtoClass) are reachable.
			final String[] effectivePackages = OptimizedSerializationHelper.withColdisModelPackage(packagesNames);
			final Map<Class<?>, String> modelClasses = new HashMap<>();
			modelClasses
					.putAll(ObjectMapperHelper.getModelClasses(
							new OptimizedSerializationHelper.NoAnnotationTypeFilter(OptimizedSerializationHelper.EXCLUDED_ANNOTATIONS, true, false),
							effectivePackages));
			// Spring's ClassPathScanningCandidateComponentProvider skips abstract classes, so the
			// initial scan misses Model/DTO parents like {@code AbstractLog} and any shared
			// hand-written abstract DTO. Walk each scanned concrete class's superclass chain and
			// add abstract parents that live in one of the configured scan packages OR anywhere
			// under the coldis library namespace (covers persistence/model bases like
			// {@code AbstractTimestampableEntity} that aren't in the user scan but are still
			// declaring classes for fields on the wire).
			final Set<Class<?>> abstractParents = new HashSet<>();
			for (final Class<?> scanned : modelClasses.keySet()) {
				Class<?> parent = scanned.getSuperclass();
				while ((parent != null) && (parent != Object.class)) {
					if (!modelClasses.containsKey(parent) && !abstractParents.contains(parent)
							&& (OptimizedSerializationHelper.isInScanPackages(parent, effectivePackages)
									|| parent.getPackageName().startsWith("org.coldis.library."))) {
						abstractParents.add(parent);
					}
					parent = parent.getSuperclass();
				}
			}
			for (final Class<?> parent : abstractParents) {
				modelClasses.put(parent, parent.getName());
			}
			// Build the (Model -> DTO) map by walking @DtoType on Models. Driven by the Model
			// side so we don't depend on @DtoOrigin on the DTO; works for hand-written DTOs too.
			final Map<Class<?>, Class<?>> modelToDto = OptimizedSerializationHelper.buildModelToDtoMap(modelClasses.keySet());
			final Set<Class<?>> pairedDtos = new HashSet<>(modelToDto.values());
			final Set<Class<?>> classesToRegister = new HashSet<>();
			for (final Class<?> clazz : modelClasses.keySet()) {
				final boolean keep = switch (scope) {
					case ALL -> true;
					case MODELS -> !pairedDtos.contains(clazz);
					case DTOS -> !modelToDto.containsKey(clazz);
				};
				if (keep) {
					classesToRegister.add(clazz);
				}
			}
			// Naming policy:
			//   ALL  — every class registered under its FQN (predictable, no shared-typeName
			//          collisions; intended for in-process use such as cloning).
			//   MODELS / DTOS — paired (Model, DTO) classes register under a shared canonical
			//          name on both peers so Fory's per-class-layer ClassDef rewrite resolves the
			//          incoming layer to the local class. Canonical name is the Model's
			//          {@link Typable#getTypeName()} when present, otherwise the Model FQN. This
			//          extends the leaf-level Typable trick to abstract intermediates so
			//          inherited fields populate across the cross-class wire (Fory keys field
			//          descriptor lookups by declaringClassFQN.fieldName, and the receiver
			//          rewrites the wire's registered class name to the local class's FQN — so
			//          both peers must register their respective parent under the same name).
			//          Unpaired classes register under their own typeName when available, else
			//          FQN.
			final Map<Class<?>, Class<?>> dtoToModel = new HashMap<>();
			for (final Map.Entry<Class<?>, Class<?>> entry : modelToDto.entrySet()) {
				dtoToModel.put(entry.getValue(), entry.getKey());
			}
			final Map<Class<?>, String> preferredNames = new HashMap<>();
			final Map<String, Class<?>> nameOwners = new HashMap<>();
			// Process non-deprecated classes first so they claim canonical type-name slots; deprecated
			// peers (kept around as old-class-name aliases) find the slot taken and fall back to their
			// FQN. Within each group, sort by FQN for deterministic ordering across runs.
			final List<Class<?>> orderedClassesToRegister = classesToRegister.stream()
					.sorted(Comparator
							.comparing((final Class<?> clazz) -> clazz.isAnnotationPresent(Deprecated.class))
							.thenComparing(Class::getName))
					.toList();
			for (final Class<?> clazz : orderedClassesToRegister) {
				String preferred;
				if (scope == RegistrationScope.ALL) {
					preferred = clazz.getName();
				}
				else {
					final Class<?> pairedModel = dtoToModel.get(clazz);
					if (pairedModel != null) {
						final String modelTypeName = OptimizedSerializationHelper.resolveTypeName(pairedModel);
						preferred = (modelTypeName != null) ? modelTypeName : pairedModel.getName();
					}
					else {
						final String typeName = OptimizedSerializationHelper.resolveTypeName(clazz);
						preferred = (typeName != null) ? typeName : clazz.getName();
					}
				}
				if (nameOwners.putIfAbsent(preferred, clazz) != null) {
					preferred = clazz.getName();
				}
				preferredNames.put(clazz, preferred);
			}
			OptimizedSerializationHelper.LOGGER.info("Optimized serializer scope={} pairs={} scanned={} registering={}.", scope, modelToDto.size(),
					modelClasses.size(), classesToRegister.size());
			OptimizedSerializationHelper.LOGGER.debug("Preferred registration names: {}", preferredNames);
			final long enumCount = classesToRegister.stream().filter(Class::isEnum).count();
			OptimizedSerializationHelper.LOGGER.debug("Registering {} enum classes.", enumCount);
			classesToRegister.forEach(clazz -> {
				final String preferredName = preferredNames.get(clazz);
				final String fallbackName = clazz.getName();
				try {
					// Use two-arg register() which splits FQN into namespace.typeName automatically.
					// The three-arg register(Class, namespace, typeName) in Fory 1.x rejects typeName
					// containing '.' even when namespace is empty, breaking our FQN-based registration.
					fory.register(clazz, preferredName);
					OptimizedSerializationHelper.LOGGER.debug("Registered {} under name '{}' (scope={}).", clazz.getName(), preferredName, scope);
				}
				catch (final IllegalArgumentException conflict) {
					if (!fallbackName.equals(preferredName)) {
						try {
							fory.register(clazz, fallbackName);
							OptimizedSerializationHelper.LOGGER.warn("Registered {} under FQN after preferred name '{}' was taken: {}", clazz.getName(),
									preferredName, conflict.getMessage());
						}
						catch (final IllegalArgumentException fallbackConflict) {
							OptimizedSerializationHelper.LOGGER.warn("Skipping optimized serialization registration of {} (preferred='{}', fallback='{}'): {}",
									clazz.getName(), preferredName, fallbackName, fallbackConflict.getMessage());
						}
					}
					else {
						OptimizedSerializationHelper.LOGGER.warn("Skipping optimized serialization registration of {} under name '{}': {}", clazz.getName(),
								preferredName, conflict.getMessage());
					}
				}
			});
		}
		return fory;
	}

	/**
	 * Whether the given class lives in (or under) any of the configured scan
	 * packages. Used to keep the abstract-parent walk scoped to the user's
	 * declared scan surface plus coldis core models.
	 */
	private static boolean isInScanPackages(
			final Class<?> clazz,
			final String[] packages) {
		final String pkg = clazz.getPackageName();
		for (final String scanPackage : packages) {
			if ((scanPackage != null) && (pkg.equals(scanPackage) || pkg.startsWith(scanPackage + "."))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Adds the coldis core model package to the user-supplied scan list when not
	 * already present.
	 */
	private static String[] withColdisModelPackage(
			final String[] packagesNames) {
		for (final String pkg : packagesNames) {
			if (OptimizedSerializationHelper.COLDIS_MODEL_PACKAGE.equals(pkg) || OptimizedSerializationHelper.COLDIS_MODEL_PACKAGE.startsWith(pkg + ".")
					|| (pkg != null && pkg.startsWith(OptimizedSerializationHelper.COLDIS_MODEL_PACKAGE + "."))) {
				return packagesNames;
			}
		}
		final String[] expanded = Arrays.copyOf(packagesNames, packagesNames.length + 1);
		expanded[packagesNames.length] = OptimizedSerializationHelper.COLDIS_MODEL_PACKAGE;
		return expanded;
	}

	/**
	 * Walks every scanned class for {@link org.coldis.library.dto.DtoType},
	 * resolves the DTO class it points at (via {@code dtoClass},
	 * {@code dtoClassName}, or the conventional {@code namespace+name}), and
	 * builds a {@code Model -> DTO} map. DTO classes that fail to load are
	 * skipped silently — the DTO project may not be on the classpath.
	 */
	private static Map<Class<?>, Class<?>> buildModelToDtoMap(
			final Set<Class<?>> scanned) {
		final Map<Class<?>, Class<?>> mapping = new HashMap<>();
		int dtoTypeAnnotated = 0;
		int dtoFqnResolved = 0;
		for (final Class<?> clazz : scanned) {
			final Annotation dtoTypeAnno = OptimizedSerializationHelper.findDtoTypeAnnotation(clazz);
			if (dtoTypeAnno == null) {
				continue;
			}
			dtoTypeAnnotated++;
			final String dtoFqn = OptimizedSerializationHelper.resolveDtoQualifiedName(clazz, dtoTypeAnno);
			if (dtoFqn == null) {
				OptimizedSerializationHelper.LOGGER.debug("@DtoType on {} did not resolve to a DTO FQN.", clazz.getName());
				continue;
			}
			dtoFqnResolved++;
			try {
				final Class<?> dtoClass = Class.forName(dtoFqn, false, clazz.getClassLoader());
				// Skip "self-DTO via parent" pairs: when the declared DTO is a shared library
				// parent of the Model (e.g. {@code HierarchyTimestampedEntity} declaring
				// {@link org.coldis.library.model.AbstractTimestampable} as its DTO), the DTO
				// class lives on BOTH sides of the wire as the Model's transitive parent. It
				// must register under its own FQN on every peer so its layer matches by name; a
				// canonical-rename on either side desyncs the parent chain.
				if (dtoClass.isAssignableFrom(clazz)) {
					OptimizedSerializationHelper.LOGGER.debug("Skipping self-parent DTO pair {} -> {} (DTO is a parent of Model).", clazz.getName(), dtoFqn);
				}
				else {
					mapping.put(clazz, dtoClass);
					OptimizedSerializationHelper.LOGGER.debug("Paired {} with DTO {}.", clazz.getName(), dtoFqn);
				}
			}
			catch (final ClassNotFoundException notLoaded) {
				OptimizedSerializationHelper.LOGGER.debug("DTO class {} declared by {} not on classpath; skipping pair.", dtoFqn, clazz.getName());
			}
		}
		OptimizedSerializationHelper.LOGGER.debug("Pair scan: scanned={} dtoTypeAnnotated={} dtoFqnResolved={} paired={}.",
				scanned.size(), dtoTypeAnnotated, dtoFqnResolved, mapping.size());
		return mapping;
	}

	/**
	 * Returns the {@code @DtoType} (or {@code @DtoTypes}-contained) annotation
	 * on the given class via reflection — the dto module is an optional
	 * dependency at runtime so we look up by name instead of taking a hard
	 * dependency.
	 */
	private static Annotation findDtoTypeAnnotation(
			final Class<?> clazz) {
		for (final Annotation annotation : clazz.getAnnotations()) {
			if ("org.coldis.library.dto.DtoType".equals(annotation.annotationType().getName())) {
				return annotation;
			}
		}
		return null;
	}

	/**
	 * Resolves the DTO class FQN for a Model annotated with {@code @DtoType}:
	 * uses {@code dtoClass} when set (and not the {@code void.class} sentinel);
	 * falls back to {@code dtoClassName}; finally derives from
	 * {@code namespace + "." + name} (with the conventional {@code Dto}
	 * suffix when {@code name} is blank).
	 */
	private static String resolveDtoQualifiedName(
			final Class<?> modelClass,
			final Annotation dtoTypeAnno) {
		try {
			final Class<? extends Annotation> annoType = dtoTypeAnno.annotationType();
			final Class<?> declaredDtoClass = (Class<?>) annoType.getMethod("dtoClass").invoke(dtoTypeAnno);
			if ((declaredDtoClass != null) && (declaredDtoClass != void.class)) {
				return declaredDtoClass.getName();
			}
			final String declaredDtoClassName = (String) annoType.getMethod("dtoClassName").invoke(dtoTypeAnno);
			if ((declaredDtoClassName != null) && !declaredDtoClassName.isBlank()) {
				return declaredDtoClassName;
			}
			final String namespace = (String) annoType.getMethod("namespace").invoke(dtoTypeAnno);
			final String name = (String) annoType.getMethod("name").invoke(dtoTypeAnno);
			final String resolvedName = ((name == null) || name.isBlank()) ? (modelClass.getSimpleName() + "Dto") : name;
			return ((namespace == null) || namespace.isBlank()) ? null : (namespace + "." + resolvedName);
		}
		catch (final Throwable error) {
			OptimizedSerializationHelper.LOGGER.debug("Could not resolve DTO FQN for {}: {}", modelClass.getName(), error.getMessage());
			return null;
		}
	}

	/**
	 * Resolves the shared logical type name for a class. Tries the
	 * {@link Typable} interface first, then falls back to a {@code
	 * getTypeName()} method (so generated DTOs that don't implement Typable
	 * still group with their origin Model under the same name). Both paths
	 * instantiate a default instance via a no-arg constructor and call the
	 * method on it. Returns null when no typeName can be resolved.
	 */
	private static String resolveTypeName(
			final Class<?> clazz) {
		String typeName = null;
		try {
			final Constructor<?> constructor = clazz.getDeclaredConstructor();
			constructor.setAccessible(true);
			final Object instance = constructor.newInstance();
			if (instance instanceof final Typable typable) {
				typeName = typable.getTypeName();
			}
			else {
				final java.lang.reflect.Method getter = clazz.getMethod("getTypeName");
				if ((getter.getReturnType() == String.class) && !java.lang.reflect.Modifier.isStatic(getter.getModifiers())) {
					typeName = (String) getter.invoke(instance);
				}
			}
		}
		catch (final Throwable error) {
			// Falls back to no shared name; class is registered with default identifier.
		}
		return typeName;
	}

}
