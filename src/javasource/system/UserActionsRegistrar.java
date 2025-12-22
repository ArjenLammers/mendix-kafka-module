package system;

import com.mendix.core.actionmanagement.IActionRegistrator;

public class UserActionsRegistrar
{
  public void registerActions(IActionRegistrator registrator)
  {
    registrator.registerUserAction(encryption.actions.DecryptString.class);
    registrator.registerUserAction(encryption.actions.EncryptString.class);
    registrator.registerUserAction(encryption.actions.GeneratePGPKeyRing.class);
    registrator.registerUserAction(encryption.actions.PGPDecryptDocument.class);
    registrator.registerUserAction(encryption.actions.PGPEncryptDocument.class);
    registrator.registerUserAction(encryption.actions.ValidatePrivateKeyRing.class);
    registrator.registerUserAction(kafka.actions.GetMessagesFromOffset.class);
    registrator.registerUserAction(kafka.actions.GetPartitionOffsets.class);
    registrator.registerUserAction(kafka.actions.GetPosition.class);
    registrator.registerUserAction(kafka.actions.ListTopics.class);
    registrator.registerUserAction(kafka.actions.SendAsynchronous.class);
    registrator.registerUserAction(kafka.actions.SendSynchronous.class);
    registrator.registerUserAction(kafka.actions.StartConsumer.class);
    registrator.registerUserAction(kafka.actions.StartFilteredProcessor.class);
    registrator.registerUserAction(kafka.actions.StartProcessor.class);
    registrator.registerUserAction(kafka.actions.StartProducer.class);
    registrator.registerUserAction(kafka.actions.StopAll.class);
    registrator.registerUserAction(kafka.actions.StopProcessor.class);
    registrator.registerUserAction(kafka.actions.StopProducer.class);
    registrator.registerUserAction(kafka.actions.StopWatchingObjects.class);
    registrator.registerUserAction(kafka.actions.WatchObjectForChange.class);
    registrator.registerUserAction(librarylogging.actions.AddLibraryLogListener.class);
    registrator.registerUserAction(librarylogging.actions.InitializeLogging.class);
    registrator.registerUserAction(librarylogging.actions.SetRootLevel.class);
    registrator.registerUserAction(mxmodelreflection.actions.JA_EnumValueCaptions.class);
    registrator.registerUserAction(mxmodelreflection.actions.JA_EnumValueLanguages.class);
    registrator.registerUserAction(mxmodelreflection.actions.JA_ReferenceObjects.class);
    registrator.registerUserAction(mxmodelreflection.actions.ReplaceToken.class);
    registrator.registerUserAction(mxmodelreflection.actions.SyncObjects.class);
    registrator.registerUserAction(mxmodelreflection.actions.TestThePattern.class);
    registrator.registerUserAction(mxmodelreflection.actions.ValidateTokensInMessage.class);
    registrator.registerUserAction(system.actions.VerifyPassword.class);
  }
}
