/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ehacks.mod.commands;

/**
 * @author radioegor146
 */
public interface ICommand {

    String getName();

    void process(String[] args);

    String getCommandDescription();

    String getCommandArgs();

    String[] autoComplete(String[] args);
}
