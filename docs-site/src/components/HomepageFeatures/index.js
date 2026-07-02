import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

const FeatureList = [
  {
    title: 'No proto authoring',
    description: (
      <>
        Write a Java interface and DTOs. KRPC treats them as the API source of
        truth and handles transport, JSON, validation, metadata, and client
        generation — no hand-written <code>.proto</code> files for normal APIs.
      </>
    ),
  },
  {
    title: 'Agent introspection',
    description: (
      <>
        A runtime metadata surface (<code>ApiMeta</code> + <code>@Doc</code> +
        constraints) plus opt-in HTTP discover/invoke endpoints give agents the
        semantic self-description that plain gRPC reflection lacks.
      </>
    ),
  },
  {
    title: 'Quarkus native, zero glue',
    description: (
      <>
        Build your service as a GraalVM native image with the{' '}
        <code>ext-rpc</code> Quarkus extension — DTO reflection is registered for
        you, no per-service native boilerplate.
      </>
    ),
  },
];

function Feature({title, description}) {
  return (
    <div className={clsx('col col--4')}>
      <div className="text--center padding-horiz--md">
        <Heading as="h3">{title}</Heading>
        <p>{description}</p>
      </div>
    </div>
  );
}

export default function HomepageFeatures() {
  return (
    <section className={styles.features}>
      <div className="container">
        <div className="row">
          {FeatureList.map((props, idx) => (
            <Feature key={idx} {...props} />
          ))}
        </div>
      </div>
    </section>
  );
}
